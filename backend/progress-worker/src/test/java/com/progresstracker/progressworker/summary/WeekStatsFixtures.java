package com.progresstracker.progressworker.summary;

import java.time.LocalDate;
import java.util.List;

/** A small, realistic week shared by the summary unit tests. */
final class WeekStatsFixtures {

    static final LocalDate MONDAY = LocalDate.of(2026, 9, 21);

    private WeekStatsFixtures() {
    }

    /** Read: 3 completions, 36 XP, streak 3 (longest 9). Run 5k: 1 completion, 10 XP, streak 1 (longest 4). */
    static WeekStats twoHabits() {
        return new WeekStats(1L, MONDAY, MONDAY.plusDays(6), List.of(
                new WeekStats.HabitWeek("Read", "DAILY", 3, 36, 3, 9),
                new WeekStats.HabitWeek("Run 5k", "DAILY", 1, 10, 1, 4)));
    }

    static WeekStats habits(WeekStats.HabitWeek... habits) {
        return new WeekStats(1L, MONDAY, MONDAY.plusDays(6), List.of(habits));
    }
}
