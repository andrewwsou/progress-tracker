package com.progresstracker.progresstracker.integration;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the race found under load: many requests completing the same habit at once all pass
 * the "already completed today?" check before any of them commits. The database's unique
 * constraint on (habit_id, completed_date) must let exactly one through, and every other request
 * must still get a normal response rather than a 500.
 *
 * This needs a real PostgreSQL: a mock cannot violate a constraint.
 */
@TestPropertySource(properties = "queue.enabled=false")
class HabitCompletionConcurrencyIT extends IntegrationTestBase {

    private static final int CONCURRENT_REQUESTS = 50;

    @Test
    void concurrentCompletionsOfOneHabitAllSucceedAndRewardExactlyOnce() throws Exception {
        String token = registerUser(uniqueEmail());
        long habitId = createDailyHabit(token, "Read");

        List<Integer> statuses = completeConcurrently(token, habitId, CONCURRENT_REQUESTS);

        assertThat(statuses).hasSize(CONCURRENT_REQUESTS).containsOnly(200);
        assertThat(entryCount(habitId)).isEqualTo(1);

        Map<String, Object> habit = jdbc.queryForMap(
                "select xp_total, current_streak, longest_streak from habit where id = ?", habitId);
        assertThat(habit.get("xp_total")).isEqualTo(10);
        assertThat(habit.get("current_streak")).isEqualTo(1);
        assertThat(habit.get("longest_streak")).isEqualTo(1);
    }

    @Test
    void completingTheSameHabitAgainTheSameDayChangesNothing() {
        String token = registerUser(uniqueEmail());
        long habitId = createDailyHabit(token, "Stretch");

        assertThat(completeHabit(token, habitId).getStatusCode().value()).isEqualTo(200);
        assertThat(completeHabit(token, habitId).getStatusCode().value()).isEqualTo(200);

        assertThat(entryCount(habitId)).isEqualTo(1);
        assertThat(xpTotal(habitId)).isEqualTo(10);
    }

    /**
     * A different race: a brand-new user completing several habits at once. Each completion tries
     * to unlock the same first-completion achievement, so all but one can hit that table's unique
     * constraint and roll back. A request must never answer 200 for a completion that was rolled back.
     */
    @Test
    void concurrentCompletionsOfDifferentHabitsNeverReportSuccessWithoutRecordingIt() throws Exception {
        String email = uniqueEmail();
        String token = registerUser(email);
        List<Long> habitIds = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            habitIds.add(createDailyHabit(token, "Habit " + i));
        }

        List<Callable<Integer>> calls = new ArrayList<>();
        for (long habitId : habitIds) {
            calls.add(() -> completeHabit(token, habitId).getStatusCode().value());
        }
        List<Integer> statuses = runAtOnce(calls);

        assertThat(statuses).isSubsetOf(200, 409).contains(200);
        for (int i = 0; i < habitIds.size(); i++) {
            int expectedEntries = statuses.get(i) == 200 ? 1 : 0;
            assertThat(entryCount(habitIds.get(i))).isEqualTo(expectedEntries);
            assertThat(xpTotal(habitIds.get(i))).isEqualTo(expectedEntries * 10);
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from user_achievement where user_id = ?", Integer.class, userIdFor(email)))
                .isEqualTo(1);
    }
}
