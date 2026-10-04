package com.progresstracker.progresstracker.integration;

import com.progresstracker.progresstracker.automation.StreakResetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The nightly streak reset is a single UPDATE. This checks which rows it touches, and that it
 * touches nothing but the streak, against a real database.
 */
@TestPropertySource(properties = "queue.enabled=false")
class StreakResetIT extends IntegrationTestBase {

    @Autowired
    private StreakResetService streakResetService;

    @Test
    void resetsOnlyStreaksThatHaveLapsedAndChangesNothingElse() {
        LocalDate today = LocalDate.now();
        LocalDate startOfLastWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
        String email = uniqueEmail();
        registerUser(email);
        long userId = userIdFor(email);

        // Daily habits: alive if completed today or yesterday.
        long dailyToday = insertHabit(userId, "DAILY", 5, today);
        long dailyYesterday = insertHabit(userId, "DAILY", 5, today.minusDays(1));
        long dailyTwoDaysAgo = insertHabit(userId, "DAILY", 5, today.minusDays(2));
        // Weekly habits: alive if completed this week or last week.
        long weeklyLastWeek = insertHabit(userId, "WEEKLY", 3, today.minusWeeks(1));
        long weeklyFirstDayOfLastWeek = insertHabit(userId, "WEEKLY", 3, startOfLastWeek);
        long weeklyDayBeforeLastWeek = insertHabit(userId, "WEEKLY", 3, startOfLastWeek.minusDays(1));
        long weeklyTwoWeeksAgo = insertHabit(userId, "WEEKLY", 3, today.minusWeeks(2));
        // Nothing to reset.
        long noStreak = insertHabit(userId, "DAILY", 0, today.minusDays(30));
        long neverCompleted = insertHabit(userId, "DAILY", 2, null);

        int resetCount = streakResetService.resetBrokenStreaks(today);

        assertThat(currentStreak(dailyToday)).isEqualTo(5);
        assertThat(currentStreak(dailyYesterday)).isEqualTo(5);
        assertThat(currentStreak(dailyTwoDaysAgo)).isZero();
        assertThat(currentStreak(weeklyLastWeek)).isEqualTo(3);
        assertThat(currentStreak(weeklyFirstDayOfLastWeek)).isEqualTo(3);
        assertThat(currentStreak(weeklyDayBeforeLastWeek)).isZero();
        assertThat(currentStreak(weeklyTwoWeeksAgo)).isZero();
        assertThat(currentStreak(noStreak)).isZero();
        assertThat(currentStreak(neverCompleted)).isEqualTo(2);
        // Other tests share this database, so the total can only be bounded from below.
        assertThat(resetCount).isGreaterThanOrEqualTo(3);

        // Only the streak moved: the history a user cares about is intact.
        Map<String, Object> row = jdbc.queryForMap(
                "select name, xp_total, longest_streak, last_completed_date from habit where id = ?", dailyTwoDaysAgo);
        assertThat(row.get("name")).isEqualTo("streak-reset");
        assertThat(row.get("xp_total")).isEqualTo(50);
        assertThat(row.get("longest_streak")).isEqualTo(9);
        assertThat(row.get("last_completed_date").toString()).isEqualTo(today.minusDays(2).toString());

        // Running it again finds nothing left to do for these habits.
        streakResetService.resetBrokenStreaks(today);
        assertThat(currentStreak(dailyYesterday)).isEqualTo(5);
        assertThat(currentStreak(weeklyLastWeek)).isEqualTo(3);
    }

    private long insertHabit(long userId, String frequency, int currentStreak, LocalDate lastCompleted) {
        return jdbc.queryForObject(
                "insert into habit (user_id, name, description, frequency, goal_period, goal_target_count, "
                        + "xp_total, current_streak, longest_streak, last_completed_date, created_at) "
                        + "values (?, 'streak-reset', '', ?, ?, 1, 50, ?, 9, ?, now()) returning id",
                Long.class, userId, frequency, frequency, currentStreak, lastCompleted);
    }

    private int currentStreak(long habitId) {
        return jdbc.queryForObject("select current_streak from habit where id = ?", Integer.class, habitId);
    }
}
