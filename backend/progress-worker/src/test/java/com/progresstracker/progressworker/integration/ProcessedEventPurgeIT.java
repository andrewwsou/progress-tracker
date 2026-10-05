package com.progresstracker.progressworker.integration;

import com.progresstracker.progressworker.service.CompletionProcessor;
import com.progresstracker.progressworker.service.ProcessedEventPurger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** The inbox is trimmed: old rows go, recent ones stay, and a late repeat still earns nothing. */
class ProcessedEventPurgeIT extends WorkerIntegrationTestBase {

    @Autowired
    private ProcessedEventPurger purger;

    @Autowired
    private CompletionProcessor processor;

    @Test
    void oldRowsAreDeletedInBatchesAndRecentOnesAreKept() {
        List<UUID> old = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            old.add(insertProcessed("now() - interval '30 days'"));
        }
        UUID recent = insertProcessed("now() - interval '1 day'");

        int deleted = purger.purgeOlderThan(Duration.ofDays(15), 2); // batches of 2, 2 and 1

        assertThat(deleted).isGreaterThanOrEqualTo(5);
        assertThat(old).noneMatch(this::exists);
        assertThat(exists(recent)).isTrue();

        jdbc.update("delete from processed_events where event_id = ?", recent);
    }

    @Test
    void theScheduledPurgeKeepsEveryRowTheQueueCouldStillRedeliver() {
        // The queue keeps a message for up to 14 days; its row is kept for 15.
        UUID stillNeeded = insertProcessed("now() - interval '14 days'");
        UUID expired = insertProcessed("now() - interval '16 days'");

        purger.purgeOnSchedule();

        assertThat(exists(stillNeeded)).isTrue();
        assertThat(exists(expired)).isFalse();

        jdbc.update("delete from processed_events where event_id = ?", stillNeeded);
    }

    @Test
    void anEventRedeliveredAfterItsRowWasPurgedEarnsNoSecondReward() {
        long userId = insertUser();
        long habitId = insertHabit(userId);
        LocalDate today = LocalDate.now();
        insertEntry(habitId, today, 0);
        UUID eventId = UUID.randomUUID();
        assertThat(processor.process(eventId, userId, habitId, today, OffsetDateTime.now())).isTrue();
        jdbc.update("update processed_events set processed_at = now() - interval '30 days' where event_id = ?", eventId);

        purger.purgeOlderThan(Duration.ofDays(15), 100);
        assertThat(exists(eventId)).isFalse();

        // The id is no longer recognised, but the completion already has its XP.
        assertThat(processor.process(eventId, userId, habitId, today, OffsetDateTime.now())).isFalse();
        assertThat(xpTotal(habitId)).isEqualTo(10);
        verify(emailService, times(1)).queueCompletionEmail(argThat(user -> user.getId() == userId), anyString());
    }

    // --- helpers ---------------------------------------------------------------------------

    private UUID insertProcessed(String processedAtSql) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into processed_events (event_id, processed_at) values (?, " + processedAtSql + ")", id);
        return id;
    }

    private boolean exists(UUID eventId) {
        return jdbc.queryForObject("select count(*) from processed_events where event_id = ?", Integer.class, eventId) > 0;
    }
}
