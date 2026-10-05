package com.progresstracker.progresstracker.integration;

import com.progresstracker.progresstracker.automation.StreakResetService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hourly streak reset is a single UPDATE. This checks which rows it touches, and that it
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

        int resetCount = streakResetService.resetBrokenStreaks(noonUtc(today));

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
        streakResetService.resetBrokenStreaks(noonUtc(today));
        assertThat(currentStreak(dailyYesterday)).isEqualTo(5);
        assertThat(currentStreak(weeklyLastWeek)).isEqualTo(3);
    }

    /**
     * Where the week boundaries fall, worked out by PostgreSQL. Each case runs at noon UTC on
     * "today" for a UTC user, with a habit just inside and just outside each cut-off.
     */
    @ParameterizedTest(name = "on {0}: daily cut-off {1}, weekly cut-off {2}")
    @CsvSource({
            // today,     yesterday,  Monday of last week
            "2026-07-13, 2026-07-12, 2026-07-06", // a Monday
            "2026-07-16, 2026-07-15, 2026-07-06", // a Thursday
            "2026-07-19, 2026-07-18, 2026-07-06", // a Sunday: still the same week
            "2026-07-20, 2026-07-19, 2026-07-13", // the next Monday: the window moves on
            "2027-01-01, 2026-12-31, 2026-12-21", // a Friday in the last ISO week of 2026 (week 53)
    })
    void streaksLapseAtTheRightDayAndWeekBoundaries(LocalDate today, LocalDate yesterday, LocalDate startOfLastWeek) {
        long userId = newUserIn("UTC");
        long dailyAlive = insertHabit(userId, "DAILY", 5, yesterday);
        long dailyLapsed = insertHabit(userId, "DAILY", 5, yesterday.minusDays(1));
        long weeklyAlive = insertHabit(userId, "WEEKLY", 3, startOfLastWeek);
        long weeklyLapsed = insertHabit(userId, "WEEKLY", 3, startOfLastWeek.minusDays(1));

        streakResetService.resetBrokenStreaks(noonUtc(today));

        assertThat(currentStreak(dailyAlive)).isEqualTo(5);
        assertThat(currentStreak(dailyLapsed)).isZero();
        assertThat(currentStreak(weeklyAlive)).isEqualTo(3);
        assertThat(currentStreak(weeklyLapsed)).isZero();
    }

    /**
     * The same habit history, judged at the same moment, for owners on either side of the date line.
     * At 03:00 UTC on 16 July it is still the evening of the 15th in Los Angeles, but already the
     * afternoon of the 16th on Kiritimati (UTC+14), where a completion on the 14th has lapsed.
     */
    @Test
    void eachHabitIsJudgedOnItsOwnersCalendar() {
        LocalDate lastCompleted = LocalDate.of(2026, 7, 14);
        long losAngelesHabit = insertHabit(newUserIn("America/Los_Angeles"), "DAILY", 4, lastCompleted);
        long kiritimatiHabit = insertHabit(newUserIn("Pacific/Kiritimati"), "DAILY", 4, lastCompleted);

        streakResetService.resetBrokenStreaks(Instant.parse("2026-07-16T03:00:00Z"));

        assertThat(currentStreak(losAngelesHabit)).isEqualTo(4); // yesterday there was the 14th
        assertThat(currentStreak(kiritimatiHabit)).isZero();      // yesterday there was the 15th
    }

    /**
     * The JVM can know zones this database does not (its time zone data can be older). Such a zone
     * must not fail the one statement that resets everyone: that owner is judged on UTC instead.
     */
    @Test
    void aZoneTheDatabaseDoesNotKnowIsReadAsUtcAndStopsNoOneElsesReset() {
        LocalDate today = LocalDate.of(2026, 7, 16);
        long unknownZone = newUserIn("Mars/Olympus_Mons");
        long unknownZoneAlive = insertHabit(unknownZone, "DAILY", 4, today.minusDays(1));
        long unknownZoneLapsed = insertHabit(unknownZone, "DAILY", 4, today.minusDays(2));
        long someoneElsesLapsed = insertHabit(newUserIn("Europe/Berlin"), "DAILY", 4, today.minusDays(2));

        streakResetService.resetBrokenStreaks(noonUtc(today));

        assertThat(currentStreak(unknownZoneAlive)).isEqualTo(4);
        assertThat(currentStreak(unknownZoneLapsed)).isZero();
        assertThat(currentStreak(someoneElsesLapsed)).isZero();
    }

    private static Instant noonUtc(LocalDate day) {
        return day.atTime(12, 0).toInstant(ZoneOffset.UTC);
    }

    private long newUserIn(String timeZone) {
        String email = uniqueEmail();
        registerUser(email);
        long userId = userIdFor(email);
        jdbc.update("update app_user set time_zone = ? where id = ?", timeZone, userId);
        return userId;
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
