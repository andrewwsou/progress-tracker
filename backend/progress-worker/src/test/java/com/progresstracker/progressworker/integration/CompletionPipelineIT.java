package com.progresstracker.progressworker.integration;

import com.progresstracker.progressworker.summary.AiSummaryWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * What the worker does to the database for each kind of message the queue can hand it:
 * a normal event, duplicates, garbage, and failures.
 */
class CompletionPipelineIT extends WorkerIntegrationTestBase {

    @Autowired
    private AiSummaryWriter aiSummaryWriter;

    @Test
    void testsCanNeverCallTheRealClaudeApi() {
        // Pinned off in WorkerIntegrationTestBase, even if the shell exports a key and the flag.
        assertThat(aiSummaryWriter.isEnabled()).isFalse();
    }

    @Test
    void grantsXpStreakAndFirstAchievementForACompletionEvent() {
        long userId = insertUser();
        long habitId = insertHabit(userId);
        LocalDate today = LocalDate.now();
        insertEntry(habitId, today, 0); // what the API writes before it publishes the event

        LocalSqs.send(QUEUE_URL, event(userId, habitId, today));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(xpTotal(habitId)).isEqualTo(10));
        assertThat(currentStreak(habitId)).isEqualTo(1);
        assertThat(xpEarned(habitId, today)).isEqualTo(10);
        assertThat(unlockedAchievements(userId)).containsExactly("FIRST_COMPLETION");
    }

    @Test
    void recordsEachHandledEventWithWhenItHappenedAndWhenItWasProcessed() {
        long userId = insertUser();
        long habitId = insertHabit(userId);
        LocalDate today = LocalDate.now();
        insertEntry(habitId, today, 0);
        UUID eventId = UUID.randomUUID();
        // A fixed instant with an offset, so the check does not depend on any clock.
        String occurredAt = "2026-01-15T08:30:00.123456+09:00";

        LocalSqs.send(QUEUE_URL, "{\"eventId\":\"" + eventId + "\",\"userId\":" + userId + ",\"habitId\":" + habitId
                + ",\"date\":\"" + today + "\",\"occurredAt\":\"" + occurredAt + "\"}");

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(xpTotal(habitId)).isEqualTo(10));
        // Both timestamps are stored, which is what lets "time from completion to reward" be queried.
        Boolean recorded = jdbc.queryForObject(
                "select occurred_at = cast(? as timestamptz) and processed_at is not null "
                        + "from processed_events where event_id = ?",
                Boolean.class, occurredAt, eventId);
        assertThat(recorded).isTrue();
    }

    @Test
    void anEventWithAnUnreadableIdOrTimestampIsStillRewarded() {
        long userId = insertUser();
        long habitId = insertHabit(userId);
        LocalDate today = LocalDate.now();
        insertEntry(habitId, today, 0);

        // The user, habit and date are valid, so this is a real completion. Dropping it would
        // lose the reward for good: the API has already marked the event as published.
        LocalSqs.send(QUEUE_URL, "{\"eventId\":\"not-a-uuid\",\"userId\":" + userId + ",\"habitId\":" + habitId
                + ",\"date\":\"" + today + "\",\"occurredAt\":1759556161}");

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(xpTotal(habitId)).isEqualTo(10));
        awaitQueueDrained();
    }

    @Test
    void anEventForAHabitThatNoLongerExistsIsDroppedWithoutRetrying() {
        long userId = insertUser();
        long habitId = insertHabit(userId);
        jdbc.update("delete from habit where id = ?", habitId); // deleted before the worker got to it
        String event = event(userId, habitId, LocalDate.now());

        LocalSqs.send(QUEUE_URL, event);

        // Gone from the queue, and not because it was retried into the dead-letter queue.
        awaitQueueDrained();
        assertThat(LocalSqs.peekBodies(DEAD_LETTER_QUEUE_URL)).doesNotContain(event);
    }

    @Test
    void stillHandlesMessagesSentBeforeEventsCarriedAnId() {
        long userId = insertUser();
        long habitId = insertHabit(userId);
        LocalDate today = LocalDate.now();
        insertEntry(habitId, today, 0);
        String oldFormat = legacyEvent(userId, habitId, today);

        LocalSqs.send(QUEUE_URL, oldFormat);
        LocalSqs.send(QUEUE_URL, oldFormat);

        awaitQueueDrained();
        assertThat(xpTotal(habitId)).isEqualTo(10);
    }

    @Test
    void extendsTheStreakAndPaysTheStreakBonusWhenYesterdayWasCompleted() {
        long userId = insertUser();
        long habitId = insertHabit(userId);
        LocalDate today = LocalDate.now();
        insertEntry(habitId, today.minusDays(1), 10);
        jdbc.update("update habit set xp_total = 10, current_streak = 1, longest_streak = 1, last_completed_date = ? where id = ?",
                today.minusDays(1), habitId);
        insertEntry(habitId, today, 0);

        LocalSqs.send(QUEUE_URL, event(userId, habitId, today));

        // Day two of a streak earns 10 base + 2 bonus.
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(xpEarned(habitId, today)).isEqualTo(12));
        assertThat(xpTotal(habitId)).isEqualTo(22);
        assertThat(currentStreak(habitId)).isEqualTo(2);
    }

    @Test
    void duplicateDeliveriesOfOneEventGrantTheRewardOnlyOnce() {
        long userId = insertUser();
        long habitId = insertHabit(userId);
        LocalDate today = LocalDate.now();
        insertEntry(habitId, today, 0);
        String event = event(userId, habitId, today);

        // SQS is at-least-once: the same event (same id) can arrive more than once.
        LocalSqs.send(QUEUE_URL, event);
        LocalSqs.send(QUEUE_URL, event);
        LocalSqs.send(QUEUE_URL, event);

        awaitQueueDrained();
        assertThat(xpTotal(habitId)).isEqualTo(10);
        assertThat(entryCount(habitId)).isEqualTo(1);
        assertThat(unlockedAchievements(userId)).containsExactly("FIRST_COMPLETION");
    }

    @Test
    void malformedMessagesAreDeletedAndDoNotBlockTheQueue() {
        List<String> poison = List.of(
                "this is not json",
                "{\"habitId\": 1}",
                "{\"userId\": 1, \"habitId\": 1, \"date\": \"not-a-date\"}");
        poison.forEach(body -> LocalSqs.send(QUEUE_URL, body));

        long userId = insertUser();
        long habitId = insertHabit(userId);
        LocalDate today = LocalDate.now();
        insertEntry(habitId, today, 0);
        LocalSqs.send(QUEUE_URL, event(userId, habitId, today));

        // The valid event behind the bad ones is still processed...
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(xpTotal(habitId)).isEqualTo(10));

        // ...and the bad ones are gone for good: off the queue, and never retried into the DLQ.
        awaitQueueDrained();
        assertThat(LocalSqs.peekBodies(DEAD_LETTER_QUEUE_URL)).doesNotContainAnyElementsOf(poison);
    }

    @Test
    void aFailureMidProcessingRollsBackAndTheRedeliveryGrantsTheRewardOnce() {
        long userId = insertUser();
        long habitId = insertHabit(userId);
        LocalDate today = LocalDate.now();
        insertEntry(habitId, today, 0);

        // The first attempt fails at the last step, after XP has been computed inside the transaction.
        doThrow(new IllegalStateException("mail service unavailable"))
                .doCallRealMethod()
                .when(emailService).queueCompletionEmail(any(), anyString());

        LocalSqs.send(QUEUE_URL, event(userId, habitId, today));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(xpTotal(habitId)).isEqualTo(10));
        awaitQueueDrained();

        // Two attempts were made, and the reward was applied exactly once.
        verify(emailService, times(2)).queueCompletionEmail(any(), anyString());
        assertThat(xpEarned(habitId, today)).isEqualTo(10);
        assertThat(xpTotal(habitId)).isEqualTo(10);
    }

    @Test
    void anEventThatAlwaysFailsIsRetriedThenMovedToTheDeadLetterQueue() {
        long owner = insertUser();
        long someoneElse = insertUser();
        long habitId = insertHabit(owner);
        // Processing rejects this every time: the user in the event does not own the habit.
        String event = event(someoneElse, habitId, LocalDate.now());

        LocalSqs.send(QUEUE_URL, event);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(LocalSqs.peekBodies(DEAD_LETTER_QUEUE_URL)).contains(event));
        assertThat(xpTotal(habitId)).isZero();
        assertThat(entryCount(habitId)).isZero();
    }

    // --- helpers ---------------------------------------------------------------------------

    /** A message as the API's outbox writes it. Each call is a new event with its own id. */
    private static String event(long userId, long habitId, LocalDate date) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"userId\":" + userId + ",\"habitId\":" + habitId
                + ",\"date\":\"" + date + "\",\"occurredAt\":\"" + OffsetDateTime.now() + "\"}";
    }

    /** A message as it was sent before events carried an id. */
    private static String legacyEvent(long userId, long habitId, LocalDate date) {
        return "{\"userId\":" + userId + ",\"habitId\":" + habitId + ",\"date\":\"" + date + "\"}";
    }

    private static void awaitQueueDrained() {
        await().atMost(TIMEOUT).until(() -> LocalSqs.isDrained(QUEUE_URL));
    }
}
