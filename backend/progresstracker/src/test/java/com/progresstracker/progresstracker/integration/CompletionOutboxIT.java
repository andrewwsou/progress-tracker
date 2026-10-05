package com.progresstracker.progresstracker.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The async path. The API must record the completion, write an event describing it in the same
 * transaction (the outbox), and leave the reward to the worker. A relay then publishes the event,
 * and keeps trying until the queue has it.
 */
// This context runs the relay on a timer; close it afterwards so it does not keep polling
// the shared test database while other test classes run.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CompletionOutboxIT extends IntegrationTestBase {

    private static final String QUEUE_NAME = "completions-" + UUID.randomUUID();
    private static final String QUEUE_URL = LocalSqs.createQueue(QUEUE_NAME);

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @DynamicPropertySource
    static void queueProperties(DynamicPropertyRegistry registry) {
        registry.add("queue.enabled", () -> "true");
        registry.add("queue.sqsUrl", () -> QUEUE_URL);
        registry.add("queue.endpointOverride", LocalSqs::endpoint);
        registry.add("outbox.relay.fixed-delay-ms", () -> "100");
    }

    @Autowired
    private JsonMapper jsonMapper;

    /** Every message read from the queue so far, by habit id. */
    private final Map<Long, List<JsonNode>> received = new HashMap<>();

    @Test
    void theCompletionAndItsEventAreWrittenTogetherAndTheRelayPublishesTheEvent() {
        String email = uniqueEmail();
        String token = registerUser(email);
        long habitId = createDailyHabit(token, "Meditate");

        assertThat(completeHabit(token, habitId).getStatusCode().value()).isEqualTo(200);

        // The API only records the completion; the reward is the worker's job.
        Map<String, Object> entry = jdbc.queryForMap(
                "select completed_date, xp_earned from habit_entries where habit_id = ?", habitId);
        assertThat(entry.get("xp_earned")).isEqualTo(0);
        assertThat(xpTotal(habitId)).isZero();

        // Exactly one event was written alongside it...
        List<UUID> eventIds = outboxEventIdsFor(habitId);
        assertThat(eventIds).hasSize(1);

        // ...and the relay delivers it to the queue and marks it published.
        JsonNode message = awaitMessagesFor(habitId, 1).get(0);
        assertThat(message.get("eventId").asString()).isEqualTo(eventIds.get(0).toString());
        assertThat(message.get("userId").asLong()).isEqualTo(userIdFor(email));
        assertThat(message.get("date").asString()).isEqualTo(entry.get("completed_date").toString());
        assertThat(OffsetDateTime.parse(message.get("occurredAt").asString())).isNotNull();
        await().atMost(TIMEOUT).until(() -> isPublished(eventIds.get(0)));
    }

    @Test
    void concurrentCompletionsOfOneHabitProduceExactlyOneEntryAndOneEvent() throws Exception {
        String token = registerUser(uniqueEmail());
        long habitId = createDailyHabit(token, "Run");

        List<Integer> statuses = completeConcurrently(token, habitId, 20);

        assertThat(statuses).hasSize(20).containsOnly(200);
        assertThat(entryCount(habitId)).isEqualTo(1);
        // The requests that lost the race failed on the completion row, before writing any event.
        List<UUID> eventIds = outboxEventIdsFor(habitId);
        assertThat(eventIds).hasSize(1);

        await().atMost(TIMEOUT).until(() -> isPublished(eventIds.get(0)));
        assertThat(awaitMessagesFor(habitId, 1)).hasSize(1);
    }

    @Test
    void completingTheSameHabitAgainTheSameDayWritesNoSecondEvent() {
        String token = registerUser(uniqueEmail());
        long habitId = createDailyHabit(token, "Stretch");

        completeHabit(token, habitId);
        completeHabit(token, habitId);
        completeHabit(token, habitId);

        assertThat(outboxEventIdsFor(habitId)).hasSize(1);
    }

    @Test
    void anEventWrittenWhileTheQueueIsDownIsPublishedOnceItComesBack() {
        String token = registerUser(uniqueEmail());
        long habitId = createDailyHabit(token, "Journal");

        LocalSqs.deleteQueue(QUEUE_URL);
        try {
            // The request still succeeds: it only writes to the database.
            assertThat(completeHabit(token, habitId).getStatusCode().value()).isEqualTo(200);
            UUID eventId = outboxEventIdsFor(habitId).get(0);

            // The relay keeps failing, and the event stays safely in the outbox.
            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).until(() -> !isPublished(eventId));
        } finally {
            LocalSqs.createQueue(QUEUE_NAME);
        }

        // Nothing was lost: the next relay run delivers it.
        UUID eventId = outboxEventIdsFor(habitId).get(0);
        await().atMost(TIMEOUT).until(() -> isPublished(eventId));
        assertThat(awaitMessagesFor(habitId, 1).get(0).get("eventId").asString()).isEqualTo(eventId.toString());
    }

    @Test
    void ifTheEventCannotBeWrittenTheCompletionIsRolledBackWithIt() {
        String token = registerUser(uniqueEmail());
        long habitId = createDailyHabit(token, "Atomic");

        // Make the database refuse the outbox insert for this one habit.
        jdbc.execute("create or replace function reject_outbox_insert() returns trigger language plpgsql "
                + "as $$ begin raise exception 'outbox insert refused by test'; end $$");
        String trigger = "reject_outbox_" + habitId;
        jdbc.execute("create trigger " + trigger + " before insert on outbox_events for each row "
                + "when ((new.payload::jsonb ->> 'habitId') = '" + habitId + "') execute function reject_outbox_insert()");
        try {
            assertThat(completeHabit(token, habitId).getStatusCode().value()).isEqualTo(500);
        } finally {
            jdbc.execute("drop trigger " + trigger + " on outbox_events");
        }

        // The completion row had already been inserted when the event failed. It is gone too:
        // the two are one transaction, so there is never a completion without its event.
        assertThat(entryCount(habitId)).isZero();
        assertThat(outboxEventIdsFor(habitId)).isEmpty();

        // And because nothing was half-written, the request can simply be retried.
        assertThat(completeHabit(token, habitId).getStatusCode().value()).isEqualTo(200);
        assertThat(entryCount(habitId)).isEqualTo(1);
        assertThat(outboxEventIdsFor(habitId)).hasSize(1);
    }

    // --- helpers ---------------------------------------------------------------------------

    private List<UUID> outboxEventIdsFor(long habitId) {
        return jdbc.queryForList(
                "select id from outbox_events where (payload::jsonb ->> 'habitId')::bigint = ?", UUID.class, habitId);
    }

    private boolean isPublished(UUID eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select published_at is not null from outbox_events where id = ?", Boolean.class, eventId));
    }

    /** Reads everything currently on the queue into {@link #received}. */
    private void drainQueue() throws Exception {
        List<Message> batch;
        while (!(batch = LocalSqs.receive(QUEUE_URL)).isEmpty()) {
            for (Message message : batch) {
                JsonNode body = jsonMapper.readTree(message.body());
                LocalSqs.delete(QUEUE_URL, message);
                received.computeIfAbsent(body.path("habitId").asLong(), id -> new ArrayList<>()).add(body);
            }
        }
    }

    /** Waits until at least {@code count} messages for this habit have arrived, then returns all of them. */
    private List<JsonNode> awaitMessagesFor(long habitId, int count) {
        await().atMost(TIMEOUT).untilAsserted(() -> {
            drainQueue();
            assertThat(received.getOrDefault(habitId, List.of()))
                    .as("messages for habit %d", habitId)
                    .hasSizeGreaterThanOrEqualTo(count);
        });
        return received.get(habitId);
    }
}
