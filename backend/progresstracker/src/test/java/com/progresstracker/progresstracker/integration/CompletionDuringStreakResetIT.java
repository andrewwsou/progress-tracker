package com.progresstracker.progresstracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The nightly streak reset running in the middle of a completion (sync mode, where the API computes
 * the reward itself). The habit's 1-day streak from two days ago has lapsed, and it is completed
 * today, so it must end with a streak of 1 whichever runs first.
 *
 * Before the fix the completion worked from the copy of the habit read at the start of the request.
 * If the reset committed in between, the new streak of 1 equalled that copy's 1, so it was never
 * written, and the reset's 0 stayed. The test forces that order: a second connection holds today's
 * entry uncommitted, so the completion stops at its entry insert while the reset runs.
 */
@TestPropertySource(properties = "queue.enabled=false")
class CompletionDuringStreakResetIT extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    @Test
    void aResetDuringACompletionDoesNotLeaveTheStreakAtZero() throws Exception {
        LocalDate today = LocalDate.now();
        String token = registerUser(uniqueEmail());
        long habitId = createDailyHabit(token, "Read");
        jdbc.update("insert into habit_entries (habit_id, completed_date, xp_earned, created_at) values (?, ?, 10, now())",
                habitId, today.minusDays(2));
        jdbc.update("update habit set current_streak = 1, longest_streak = 1, xp_total = 10, last_completed_date = ? "
                + "where id = ?", today.minusDays(2), habitId);

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement entry = holder.prepareStatement(
                    "insert into habit_entries (habit_id, completed_date, xp_earned, created_at) values (?, ?, 0, now())")) {
                entry.setLong(1, habitId);
                entry.setObject(2, today);
                entry.executeUpdate();
            }

            // The completion reads the habit, then waits on the entry row.
            Future<ResponseEntity<JsonNode>> completion = threads.submit(() -> completeHabit(token, habitId));
            await().atMost(Duration.ofSeconds(30)).until(() -> waitingOnALock("%insert into habit_entries%"));

            // The nightly reset for this habit: last completed before yesterday, so its streak goes to 0.
            // With the fix it waits for the completion's row lock; without it, it commits at once.
            Future<Integer> reset = threads.submit(() -> jdbc.update(
                    "update habit set current_streak = 0 where id = ? and current_streak > 0 and last_completed_date < ?",
                    habitId, today.minusDays(1)));
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> reset.isDone() || waitingOnALock("%update habit set current_streak = 0%"));

            // Let the completion go on.
            holder.rollback();
            ResponseEntity<JsonNode> response = completion.get(30, TimeUnit.SECONDS);
            reset.get(30, TimeUnit.SECONDS);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().get("currentStreak").asInt()).isEqualTo(1);
        } finally {
            threads.shutdownNow();
        }

        assertThat(jdbc.queryForObject("select current_streak from habit where id = ?", Integer.class, habitId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select last_completed_date from habit where id = ?", LocalDate.class, habitId))
                .isEqualTo(today);
        assertThat(xpTotal(habitId)).isEqualTo(20);
    }

    private boolean waitingOnALock(String queryPattern) {
        return jdbc.queryForObject(
                "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and query ilike ?",
                Integer.class, queryPattern) > 0;
    }
}
