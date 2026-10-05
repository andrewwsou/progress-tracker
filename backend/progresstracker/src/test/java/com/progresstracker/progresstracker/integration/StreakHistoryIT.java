package com.progresstracker.progresstracker.integration;

import com.progresstracker.progresstracker.repository.HabitEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sync mode counts streaks from the completion history, with the same queries as the worker, so
 * a deployment's mode never changes what a user earns.
 */
@TestPropertySource(properties = "queue.enabled=false")
class StreakHistoryIT extends IntegrationTestBase {

    @Autowired
    private HabitEntryRepository habitEntryRepository;

    /**
     * Done Monday to Saturday of last week as a daily habit (a 6-day streak), then switched to
     * weekly and done this week. The worker counts that as two weeks in a row: streak 2, 12 XP,
     * and no 7-day achievement. Sync mode must agree, rather than add a week to the 6 days.
     */
    @Test
    void afterTheFrequencyChangesTheStreakIsCountedInTheNewUnit() {
        String email = uniqueEmail();
        String token = registerUser(email);
        long habitId = createDailyHabit(token, "Swim");
        LocalDate lastMonday = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
        for (int day = 0; day < 6; day++) {
            insertEntry(habitId, lastMonday.plusDays(day), 10 + day * 2);
        }
        jdbc.update("update habit set current_streak = 6, longest_streak = 6, xp_total = 90, last_completed_date = ? "
                + "where id = ?", lastMonday.plusDays(5), habitId);
        assertThat(send(HttpMethod.PUT, "/api/habits/" + habitId, token, Map.of("name", "Swim", "frequency", "WEEKLY"))
                .getStatusCode().value()).isEqualTo(200);

        ResponseEntity<JsonNode> response = completeHabit(token, habitId);

        assertThat(response.getBody().get("currentStreak").asInt()).isEqualTo(2);
        assertThat(response.getBody().get("longestStreak").asInt()).isEqualTo(6);
        assertThat(xpTotal(habitId)).isEqualTo(90 + 12);
        assertThat(jdbc.queryForObject("select count(*) from user_achievement ua join achievement a on a.id = ua.achievement_id "
                + "where ua.user_id = ? and a.code = 'STREAK_7'", Integer.class, userIdFor(email))).isZero();
    }

    /** ISO weeks, which are what the streak counts, run across the change of year. */
    @Test
    void weeksRunOnAcrossTheIsoYearBoundary() {
        long habitId = createDailyHabit(registerUser(uniqueEmail()), "Plan the week");
        insertEntry(habitId, LocalDate.of(2026, 12, 23), 10); // Wednesday, 2026-W52
        insertEntry(habitId, LocalDate.of(2026, 12, 28), 12); // Monday, 2026-W53
        insertEntry(habitId, LocalDate.of(2027, 1, 4), 14);   // Monday, 2027-W01

        // 2027-W01 starts on Monday 4 January; the two weeks before it were both done.
        assertThat(habitEntryRepository.weeklyStreakEndingAt(habitId, LocalDate.of(2027, 1, 4), LocalDate.of(2027, 1, 10)))
                .isEqualTo(3);
        // 2026-W53 runs from Monday 28 December to Sunday 3 January.
        assertThat(habitEntryRepository.weeklyStreakEndingAt(habitId, LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3)))
                .isEqualTo(2);
        // A week with nothing in it ends the run: 2027-W02 was not done.
        assertThat(habitEntryRepository.weeklyStreakEndingAt(habitId, LocalDate.of(2027, 1, 11), LocalDate.of(2027, 1, 17)))
                .isZero();
    }

    @Test
    void daysRunOnAcrossTheChangeOfYearAndStopAtAMissedDay() {
        long habitId = createDailyHabit(registerUser(uniqueEmail()), "Read");
        for (LocalDate day = LocalDate.of(2026, 12, 29); !day.isAfter(LocalDate.of(2027, 1, 2)); day = day.plusDays(1)) {
            insertEntry(habitId, day, 10);
        }
        insertEntry(habitId, LocalDate.of(2026, 12, 27), 10); // before a missed day, the 28th

        assertThat(habitEntryRepository.dailyStreakEndingAt(habitId, LocalDate.of(2027, 1, 2))).isEqualTo(5);
        assertThat(habitEntryRepository.dailyStreakEndingAt(habitId, LocalDate.of(2027, 1, 3))).isZero();
    }

    private void insertEntry(long habitId, LocalDate date, int xp) {
        jdbc.update("insert into habit_entries (habit_id, completed_date, xp_earned, created_at) values (?, ?, ?, now())",
                habitId, date, xp);
    }
}
