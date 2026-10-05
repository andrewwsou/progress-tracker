package com.progresstracker.progressworker.integration;

import com.progresstracker.progressworker.service.CompletionProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The API's hourly streak reset running in the middle of a reward. The reward reads the habit,
 * the reset zeroes the habit's lapsed streak and commits, and then the reward writes. The reward
 * must win: the habit was just completed, so its streak is 1, not 0.
 *
 * Before the fix the worker wrote back the copy it had read, and a streak of 1 equal to that
 * copy's 1 was not written at all, so the reset's 0 stayed. The test forces the interleaving: a
 * second connection holds today's entry row uncommitted, so the reward stops at its entry insert
 * (after reading the habit) until the test has run the reset.
 */
class StreakResetRaceIT extends WorkerIntegrationTestBase {

    @Autowired
    private CompletionProcessor processor;

    @Autowired
    private DataSource dataSource;

    @Test
    void aStreakResetDuringARewardDoesNotOverwriteTheNewStreak() throws Exception {
        LocalDate today = LocalDate.now();
        long userId = insertUser();
        long habitId = insertHabit(userId);
        // A 1-day streak from three days ago, so tonight's reset would zero it.
        insertEntry(habitId, today.minusDays(3), 10);
        jdbc.update("update habit set current_streak = 1, longest_streak = 1, xp_total = 10, last_completed_date = ? "
                + "where id = ?", today.minusDays(3), habitId);

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement entry = holder.prepareStatement(
                    "insert into habit_entries (habit_id, completed_date, xp_earned, created_at) values (?, ?, 0, now())")) {
                entry.setLong(1, habitId);
                entry.setObject(2, today);
                entry.executeUpdate();
            }

            // The reward reads the habit (streak 1, last completed three days ago), then waits on the entry.
            Future<Boolean> reward = worker.submit(() ->
                    processor.process(UUID.randomUUID(), userId, habitId, today, OffsetDateTime.now()));
            await().atMost(TIMEOUT).until(() -> jdbc.queryForObject(
                    "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and query ilike ?",
                    Integer.class, "%insert into habit_entries%") > 0);

            // The hourly reset for this habit: its last completion is before yesterday.
            int reset = jdbc.update("update habit set current_streak = 0 "
                    + "where id = ? and current_streak > 0 and last_completed_date < ?", habitId, today.minusDays(1));
            assertThat(reset).isEqualTo(1);

            // Let the reward continue.
            holder.rollback();
            assertThat(reward.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            worker.shutdownNow();
        }

        assertThat(currentStreak(habitId)).isEqualTo(1);
        assertThat(xpTotal(habitId)).isEqualTo(20);
        assertThat(jdbc.queryForObject("select last_completed_date from habit where id = ?", LocalDate.class, habitId))
                .isEqualTo(today);
    }
}
