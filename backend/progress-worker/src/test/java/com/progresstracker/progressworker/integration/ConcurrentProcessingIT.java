package com.progresstracker.progressworker.integration;

import com.progresstracker.progressworker.model.Habit;
import com.progresstracker.progressworker.repository.HabitRepository;
import com.progresstracker.progressworker.service.CompletionProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * What happens when more than one worker handles events at the same moment. The poller in this
 * JVM is single-threaded, so each test calls the processor from several threads released together,
 * which is what two worker instances (or a redelivery racing the original) look like to the database.
 *
 * Races are a matter of timing, so every scenario is repeated many times on fresh data.
 * Before the inbox table and the per-user lock existed, all three scenarios failed.
 */
class ConcurrentProcessingIT extends WorkerIntegrationTestBase {

    private static final int ROUNDS = 25;

    @Autowired
    private CompletionProcessor processor;

    @Autowired
    private HabitRepository habitRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void theSameEventHandledTwiceAtOnceRewardsAndEmailsOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long userId = insertUser();
            long habitId = insertHabit(userId);
            LocalDate today = LocalDate.now();
            insertEntry(habitId, today, 0);
            UUID eventId = UUID.randomUUID();

            List<Throwable> outcomes = runAtOnce(List.of(
                    () -> processor.process(eventId, userId, habitId, today, OffsetDateTime.now()),
                    () -> processor.process(eventId, userId, habitId, today, OffsetDateTime.now())));

            assertThat(outcomes).as("round %d", round).containsOnlyNulls();
            assertThat(xpTotal(habitId)).isEqualTo(10);
            assertThat(xpEarned(habitId, today)).isEqualTo(10);
            assertThat(unlockedAchievements(userId)).containsExactly("FIRST_COMPLETION");
            assertThat(processedCount(eventId)).isEqualTo(1);
            verify(emailService, times(1)).queueCompletionEmail(argThat(user -> user.getId() == userId), any());
        }
    }

    @Test
    void twoDaysOfTheSameHabitHandledAtOnceLoseNoXp() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long userId = insertUser();
            long habitId = insertHabit(userId);
            LocalDate today = LocalDate.now();
            LocalDate yesterday = today.minusDays(1);
            insertEntry(habitId, yesterday, 0);
            insertEntry(habitId, today, 0);

            List<Throwable> outcomes = runAtOnce(List.of(
                    () -> processor.process(UUID.randomUUID(), userId, habitId, yesterday, OffsetDateTime.now()),
                    () -> processor.process(UUID.randomUUID(), userId, habitId, today, OffsetDateTime.now())));

            assertThat(outcomes).as("round %d", round).containsOnlyNulls();
            // Day one earns 10, day two of the streak earns 12, in whichever order they were handled.
            assertThat(xpEarned(habitId, yesterday)).isEqualTo(10);
            assertThat(xpEarned(habitId, today)).isEqualTo(12);
            assertThat(xpTotal(habitId)).as("round %d: habit total equals the sum of its entries", round).isEqualTo(22);
            assertThat(currentStreak(habitId)).isEqualTo(2);
        }
    }

    @Test
    void differentHabitsOfOneNewUserHandledAtOnceUnlockTheFirstAchievementOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long userId = insertUser();
            long firstHabit = insertHabit(userId);
            long secondHabit = insertHabit(userId);
            LocalDate today = LocalDate.now();
            insertEntry(firstHabit, today, 0);
            insertEntry(secondHabit, today, 0);

            List<Throwable> outcomes = runAtOnce(List.of(
                    () -> processor.process(UUID.randomUUID(), userId, firstHabit, today, OffsetDateTime.now()),
                    () -> processor.process(UUID.randomUUID(), userId, secondHabit, today, OffsetDateTime.now())));

            assertThat(outcomes).as("round %d", round).containsOnlyNulls();
            assertThat(xpTotal(firstHabit)).isEqualTo(10);
            assertThat(xpTotal(secondHabit)).isEqualTo(10);
            assertThat(unlockedAchievements(userId)).containsExactly("FIRST_COMPLETION");
        }
    }

    @Test
    void theSameCompletionDescribedByTwoDifferentEventsAtOnceIsRewardedOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long userId = insertUser();
            long habitId = insertHabit(userId);
            LocalDate today = LocalDate.now();
            insertEntry(habitId, today, 0);

            // Different event ids, so the inbox does not catch it; the per-user lock and the
            // "already rewarded" check on the entry do.
            List<Throwable> outcomes = runAtOnce(List.of(
                    () -> processor.process(UUID.randomUUID(), userId, habitId, today, OffsetDateTime.now()),
                    () -> processor.process(UUID.randomUUID(), userId, habitId, today, OffsetDateTime.now())));

            assertThat(outcomes).as("round %d", round).containsOnlyNulls();
            assertThat(xpTotal(habitId)).isEqualTo(10);
            verify(emailService, times(1)).queueCompletionEmail(argThat(user -> user.getId() == userId), any());
        }
    }

    /**
     * Events are not delivered in order. Here an older day, which completes a 7-day streak,
     * is handled after a newer day that started a fresh one.
     */
    @Test
    void anOlderDayHandledLateStillEarnsItsStreakButDoesNotRewindTheCurrentOne() {
        long userId = insertUser();
        long habitId = insertHabit(userId);
        LocalDate today = LocalDate.now();
        // Seven days in a row, ending two days ago. The last of them has not been rewarded yet.
        for (int daysAgo = 8; daysAgo >= 3; daysAgo--) {
            insertEntry(habitId, today.minusDays(daysAgo), 10);
        }
        insertEntry(habitId, today.minusDays(2), 0);
        // Yesterday was missed; today starts a new streak.
        insertEntry(habitId, today, 0);

        processor.process(UUID.randomUUID(), userId, habitId, today, OffsetDateTime.now());
        processor.process(UUID.randomUUID(), userId, habitId, today.minusDays(2), OffsetDateTime.now());

        assertThat(xpEarned(habitId, today)).isEqualTo(10);               // day one of the new streak
        assertThat(xpEarned(habitId, today.minusDays(2))).isEqualTo(22);  // day seven of the old one
        assertThat(currentStreak(habitId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select longest_streak from habit where id = ?", Integer.class, habitId))
                .isEqualTo(7);
        assertThat(unlockedAchievements(userId)).containsExactly("FIRST_COMPLETION", "STREAK_7");
    }

    /**
     * The API and the worker both update the habit row. Rewarding a completion must write only
     * the XP and streak columns, or it would put back the name it loaded before an edit landed.
     */
    @Test
    void rewardingACompletionDoesNotUndoAnEditMadeInTheMeantime() {
        long userId = insertUser();
        long habitId = insertHabit(userId);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Habit habit = habitRepository.findById(habitId).orElseThrow();
            // After the worker loaded the row, the user renames the habit through the API.
            jdbc.update("update habit set name = 'Renamed' where id = ?", habitId);
            habit.setXpTotal(10);
        });

        assertThat(jdbc.queryForObject("select name from habit where id = ?", String.class, habitId))
                .isEqualTo("Renamed");
        assertThat(xpTotal(habitId)).isEqualTo(10);
    }

    private int processedCount(UUID eventId) {
        return jdbc.queryForObject("select count(*) from processed_events where event_id = ?", Integer.class, eventId);
    }
}
