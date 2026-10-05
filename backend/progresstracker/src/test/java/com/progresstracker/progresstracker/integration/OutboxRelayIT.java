package com.progresstracker.progresstracker.integration;

import com.progresstracker.progresstracker.outbox.OutboxRelay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The relay on its own, with no timer running: each test calls it by hand, so it decides
 * exactly when a run happens and nothing publishes rows behind its back.
 */
class OutboxRelayIT extends IntegrationTestBase {

    private static final String QUEUE_URL = LocalSqs.createQueue("relay-" + UUID.randomUUID());

    private static final Duration NO_WAITING = Duration.ofSeconds(10);

    @DynamicPropertySource
    static void queueProperties(DynamicPropertyRegistry registry) {
        registry.add("queue.enabled", () -> "true");
        registry.add("queue.sqsUrl", () -> QUEUE_URL);
        registry.add("queue.endpointOverride", LocalSqs::endpoint);
        registry.add("outbox.relay.scheduling-enabled", () -> "false");
    }

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void startWithNothingWaiting() {
        // Rows other test classes left unpublished would otherwise be claimed first.
        jdbc.update("update outbox_events set published_at = now() where published_at is null");
    }

    /**
     * FOR UPDATE SKIP LOCKED: a relay passes over rows another relay holds instead of queueing up
     * behind it. With a plain FOR UPDATE the first call below would block until the timeout.
     */
    @Test
    void aRelaySkipsRowsAnotherRelayHasClaimedInsteadOfWaitingForThem() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(insertUnpublished("now() - interval '" + (20 - i) + " seconds'"));
        }
        List<UUID> claimedElsewhere = ids.subList(0, 10); // the ten oldest
        List<UUID> free = ids.subList(10, 20);

        try (Connection otherInstance = dataSource.getConnection()) {
            otherInstance.setAutoCommit(false);
            try {
                // Another API instance has locked the ten oldest rows and is mid-publish.
                try (PreparedStatement lock = otherInstance.prepareStatement(
                        "select id from outbox_events where id = any (?) for update")) {
                    lock.setArray(1, otherInstance.createArrayOf("uuid", claimedElsewhere.toArray()));
                    lock.executeQuery();
                }

                // This relay takes the next ten straight away...
                assertThat(assertTimeoutPreemptively(NO_WAITING, relay::publishNextBatch)).isEqualTo(10);
                assertThat(publishedCount(free)).isEqualTo(10);
                assertThat(publishedCount(claimedElsewhere)).isZero();

                // ...and then finds nothing left that it is allowed to touch.
                assertThat(assertTimeoutPreemptively(NO_WAITING, relay::publishNextBatch)).isZero();
            } finally {
                otherInstance.rollback(); // the other instance failed without publishing
            }
        }

        // Its rows are free again, and the next run publishes them.
        assertThat(relay.publishNextBatch()).isEqualTo(10);
        assertThat(publishedCount(ids)).isEqualTo(20);

        Map<String, Integer> deliveries = drainQueueByEventId();
        for (UUID id : ids) {
            assertThat(deliveries.get(id.toString())).as("deliveries of event %s", id).isEqualTo(1);
        }
    }

    @Test
    void severalRelaysRunningAtOncePublishEachEventExactlyOnce() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            ids.add(insertUnpublished("now() - interval '" + (30 - i) + " seconds'"));
        }
        assertThat(publishedCount(ids)).isZero(); // three batches' worth, all still waiting

        // Six relays released together, as if six API instances ran their timers at once.
        List<Callable<Integer>> relays = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            relays.add(() -> {
                relay.publishPending();
                return 0;
            });
        }
        runAtOnce(relays);
        // A relay that found every remaining row locked stops early, so finish off any stragglers.
        relay.publishPending();

        assertThat(publishedCount(ids)).isEqualTo(30);
        Map<String, Integer> deliveries = drainQueueByEventId();
        for (UUID id : ids) {
            assertThat(deliveries.get(id.toString())).as("deliveries of event %s", id).isEqualTo(1);
        }
    }

    @Test
    void thePurgeRemovesOldPublishedEventsAndNeverAnUnpublishedOne() {
        UUID oldPublished = insertRow("now() - interval '30 days'", "now() - interval '30 days'");
        UUID recentPublished = insertRow("now() - interval '1 day'", "now() - interval '1 day'");
        // Just as old, but it never reached the queue. Deleting it would lose a reward.
        UUID oldUnpublished = insertRow("now() - interval '30 days'", "null");

        relay.purgePublishedOlderThan(Duration.ofDays(7));

        assertThat(rowExists(oldPublished)).isFalse();
        assertThat(rowExists(recentPublished)).isTrue();
        assertThat(rowExists(oldUnpublished)).isTrue();

        jdbc.update("delete from outbox_events where id in (?, ?)", recentPublished, oldUnpublished);
    }

    // --- helpers ---------------------------------------------------------------------------

    private UUID insertUnpublished(String createdAtSql) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into outbox_events (id, type, payload, created_at) values (?, 'test', ?, " + createdAtSql + ")",
                id, "{\"eventId\":\"" + id + "\"}");
        return id;
    }

    private UUID insertRow(String createdAtSql, String publishedAtSql) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into outbox_events (id, type, payload, created_at, published_at) "
                + "values (?, 'test', '{}', " + createdAtSql + ", " + publishedAtSql + ")", id);
        return id;
    }

    private int publishedCount(List<UUID> ids) {
        int count = 0;
        for (UUID id : ids) {
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "select published_at is not null from outbox_events where id = ?", Boolean.class, id))) {
                count++;
            }
        }
        return count;
    }

    private boolean rowExists(UUID id) {
        return jdbc.queryForObject("select count(*) from outbox_events where id = ?", Integer.class, id) == 1;
    }

    private Map<String, Integer> drainQueueByEventId() throws Exception {
        Map<String, Integer> deliveries = new HashMap<>();
        List<Message> batch;
        while (!(batch = LocalSqs.receive(QUEUE_URL)).isEmpty()) {
            for (Message message : batch) {
                deliveries.merge(jsonMapper.readTree(message.body()).path("eventId").asString(), 1, Integer::sum);
                LocalSqs.delete(QUEUE_URL, message);
            }
        }
        return deliveries;
    }
}
