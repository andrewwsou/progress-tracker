package com.progresstracker.progressworker.summary;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** What one user did in one week: the facts a summary may use, and nothing else. */
public record WeekStats(long userId, LocalDate weekStart, LocalDate weekEnd, List<HabitWeek> habits) {

    /** One habit's week. Streaks are counted in days for daily habits and in weeks for weekly ones. */
    public record HabitWeek(
            String name,
            String frequency,
            int completions,
            int xpEarned,
            int currentStreak,
            int longestStreak
    ) {
        public String streakUnit() {
            return "WEEKLY".equals(frequency) ? "weeks" : "days";
        }
    }

    public int totalCompletions() {
        return habits.stream().mapToInt(HabitWeek::completions).sum();
    }

    public int totalXpEarned() {
        return habits.stream().mapToInt(HabitWeek::xpEarned).sum();
    }

    public Set<String> habitNames() {
        Set<String> names = new LinkedHashSet<>();
        habits.forEach(habit -> names.add(habit.name()));
        return names;
    }
}
