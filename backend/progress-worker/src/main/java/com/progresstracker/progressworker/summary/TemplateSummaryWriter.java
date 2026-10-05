package com.progresstracker.progressworker.summary;

import org.springframework.stereotype.Component;

import java.util.Comparator;

/**
 * Writes a plain summary from the numbers alone, so every user still gets one. Used when Claude
 * is off (SUMMARY_LLM_ENABLED is not true, no API key is set, or the model is not on the
 * allow-list), when the week is too big to send, when a daily cap on calls or tokens is reached,
 * and whenever the model's answer cannot be used.
 */
@Component
public class TemplateSummaryWriter {

    public SummaryOutput write(WeekStats stats) {
        if (stats.habits().isEmpty()) {
            return new SummaryOutput(
                    "No habits to summarize this week",
                    "Add a habit and complete it to see your weekly summary here.",
                    null);
        }

        int completions = stats.totalCompletions();
        String headline = plural(completions, "completion") + " and " + stats.totalXpEarned() + " XP this week";

        WeekStats.HabitWeek strongest = stats.habits().stream()
                .max(Comparator.comparingInt(WeekStats.HabitWeek::completions)
                        .thenComparingInt(WeekStats.HabitWeek::currentStreak)
                        .thenComparing(WeekStats.HabitWeek::name, Comparator.reverseOrder()))
                .orElseThrow();
        WeekStats.HabitWeek focus = stats.habits().stream()
                .min(Comparator.comparingInt(WeekStats.HabitWeek::completions)
                        .thenComparingInt(WeekStats.HabitWeek::currentStreak)
                        .thenComparing(WeekStats.HabitWeek::name))
                .orElseThrow();
        WeekStats.HabitWeek longest = stats.habits().stream()
                .max(Comparator.comparingInt(WeekStats.HabitWeek::longestStreak))
                .orElseThrow();

        StringBuilder body = new StringBuilder()
                .append("Your most consistent habit was ").append(strongest.name())
                .append(" with ").append(plural(strongest.completions(), "completion")).append('.');
        if (longest.longestStreak() > 1) {
            body.append(" Your longest streak is ").append(longest.longestStreak()).append(' ')
                    .append(longest.streakUnit()).append(" on ").append(longest.name()).append('.');
        }
        if (stats.habits().size() > 1 && !focus.equals(strongest)) {
            body.append(" Next week, give ").append(focus.name()).append(" a little more attention.");
        }
        return new SummaryOutput(headline, body.toString(), focus.name());
    }

    private static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
