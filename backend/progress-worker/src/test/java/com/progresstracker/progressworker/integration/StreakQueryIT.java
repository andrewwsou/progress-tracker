package com.progresstracker.progressworker.integration;

import com.progresstracker.progressworker.repository.HabitEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The streak is computed by one SQL statement. These tests check that statement against the
 * obvious way of counting (walk back one day or week at a time) on many random histories,
 * including ones that cross a year boundary and a 53-week year.
 */
class StreakQueryIT extends WorkerIntegrationTestBase {

    /** Fixed, so every run sees the same histories. 2026 has 53 ISO weeks, and this is just after it. */
    private static final LocalDate TODAY = LocalDate.of(2027, 1, 20);

    @Autowired
    private HabitEntryRepository repository;

    @Test
    void theDailyStreakMatchesCountingBackOneDayAtATimeOnRandomHistories() {
        Random random = new Random(20270120);
        for (int round = 0; round < 60; round++) {
            long habitId = insertHabit(insertUser());
            Set<LocalDate> days = randomDays(random, 60, 0.3 + random.nextDouble() * 0.69);
            days.forEach(day -> insertEntry(habitId, day, 10));

            for (int back = 0; back < 12; back++) {
                LocalDate end = TODAY.minusDays(back);
                assertThat(repository.dailyStreakEndingAt(habitId, end))
                        .as("round %d, streak ending %s, completed days %s", round, end, days)
                        .isEqualTo(dailyStreakByCounting(days, end));
            }
        }
    }

    @Test
    void theWeeklyStreakMatchesCountingBackOneWeekAtATimeOnRandomHistories() {
        Random random = new Random(20270121);
        for (int round = 0; round < 60; round++) {
            long habitId = insertHabit(insertUser());
            Set<LocalDate> days = randomDays(random, 140, 0.03 + random.nextDouble() * 0.25);
            days.forEach(day -> insertEntry(habitId, day, 10));

            for (int back = 0; back < 28; back += 3) {
                LocalDate date = TODAY.minusDays(back);
                LocalDate weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                assertThat(repository.weeklyStreakEndingAt(habitId, weekStart, weekStart.plusDays(6)))
                        .as("round %d, streak ending in the week of %s, completed days %s", round, date, days)
                        .isEqualTo(weeklyStreakByCounting(days, weekStart));
            }
        }
    }

    @Test
    void aYearOfDaysIsOneStreakAndAGapEndsIt() {
        long habitId = insertHabit(insertUser());
        for (int back = 0; back < 365; back++) {
            if (back != 100) { // one missed day, 100 days ago
                insertEntry(habitId, TODAY.minusDays(back), 10);
            }
        }

        assertThat(repository.dailyStreakEndingAt(habitId, TODAY)).isEqualTo(100);
        assertThat(repository.dailyStreakEndingAt(habitId, TODAY.minusDays(101))).isEqualTo(264);
    }

    @Test
    void daysAfterTheGivenDateAreIgnoredAndAnUncompletedDateHasNoStreak() {
        long habitId = insertHabit(insertUser());
        insertEntry(habitId, TODAY.minusDays(3), 10);
        insertEntry(habitId, TODAY.minusDays(2), 10);
        insertEntry(habitId, TODAY, 10); // later than the dates asked about below

        assertThat(repository.dailyStreakEndingAt(habitId, TODAY.minusDays(2))).isEqualTo(2);
        assertThat(repository.dailyStreakEndingAt(habitId, TODAY.minusDays(1))).isZero();
        assertThat(repository.dailyStreakEndingAt(insertHabit(insertUser()), TODAY)).isZero();
    }

    private static Set<LocalDate> randomDays(Random random, int daysBack, double density) {
        Set<LocalDate> days = new TreeSet<>();
        for (int back = 0; back < daysBack; back++) {
            if (random.nextDouble() < density) {
                days.add(TODAY.minusDays(back));
            }
        }
        return days;
    }

    private static long dailyStreakByCounting(Set<LocalDate> days, LocalDate end) {
        long streak = 0;
        for (LocalDate day = end; days.contains(day); day = day.minusDays(1)) {
            streak++;
        }
        return streak;
    }

    private static long weeklyStreakByCounting(Set<LocalDate> days, LocalDate weekStart) {
        LocalDate weekEnd = weekStart.plusDays(6);
        Set<LocalDate> completedWeeks = days.stream()
                .filter(day -> !day.isAfter(weekEnd))
                .map(day -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
                .collect(Collectors.toSet());
        long streak = 0;
        for (LocalDate week = weekStart; completedWeeks.contains(week); week = week.minusWeeks(1)) {
            streak++;
        }
        return streak;
    }
}
