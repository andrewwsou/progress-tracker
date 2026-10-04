package com.progresstracker.progressworker.summary;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a model-written summary against the data it was written from, before anyone sees it.
 * Structured output guarantees the shape; this checks the content:
 * <ul>
 *   <li>the headline and body are present and not too long,</li>
 *   <li>the focus habit is one of the user's habits, spelled exactly,</li>
 *   <li>every number in the text appears in the data, so the model cannot invent a statistic.</li>
 * </ul>
 */
@Component
public class SummaryValidator {

    static final int MAX_HEADLINE_LENGTH = 100;
    static final int MAX_BODY_LENGTH = 500;

    /** Whole numbers, allowing thousands separators: 7, 106, 1,250. */
    private static final Pattern NUMBER = Pattern.compile("\\d{1,3}(?:,\\d{3})+|\\d+");

    /** @return what is wrong with the summary, or empty if it can be shown */
    public Optional<String> findProblem(SummaryOutput output, WeekStats stats) {
        if (output.headline() == null || output.headline().isBlank()) {
            return Optional.of("headline is empty");
        }
        if (output.headline().length() > MAX_HEADLINE_LENGTH) {
            return Optional.of("headline is " + output.headline().length() + " characters");
        }
        if (output.body() == null || output.body().isBlank()) {
            return Optional.of("body is empty");
        }
        if (output.body().length() > MAX_BODY_LENGTH) {
            return Optional.of("body is " + output.body().length() + " characters");
        }
        if (output.focusHabit() == null || !stats.habitNames().contains(output.focusHabit())) {
            return Optional.of("focusHabit is not one of the user's habits");
        }

        Set<Long> allowed = numbersInData(stats);
        Matcher matcher = NUMBER.matcher(output.headline() + "\n" + output.body());
        while (matcher.find()) {
            String digits = matcher.group().replace(",", "");
            if (digits.length() > 9 || !allowed.contains(Long.parseLong(digits))) {
                return Optional.of("mentions " + matcher.group() + ", which is not in the data");
            }
        }
        return Optional.empty();
    }

    /** Every number the model may use: the statistics, the dates of the week, and digits in habit names. */
    private static Set<Long> numbersInData(WeekStats stats) {
        Set<Long> numbers = new HashSet<>();
        numbers.add((long) stats.totalCompletions());
        numbers.add((long) stats.totalXpEarned());
        numbers.add((long) stats.habits().size());
        numbers.add(7L); // days in a week
        for (var date : new java.time.LocalDate[]{stats.weekStart(), stats.weekEnd()}) {
            numbers.add((long) date.getDayOfMonth());
            numbers.add((long) date.getMonthValue());
            numbers.add((long) date.getYear());
        }
        for (WeekStats.HabitWeek habit : stats.habits()) {
            numbers.add((long) habit.completions());
            numbers.add((long) habit.xpEarned());
            numbers.add((long) habit.currentStreak());
            numbers.add((long) habit.longestStreak());
            Matcher inName = NUMBER.matcher(habit.name());
            while (inName.find()) {
                String digits = inName.group().replace(",", "");
                if (digits.length() <= 9) {
                    numbers.add(Long.parseLong(digits));
                }
            }
        }
        return numbers;
    }
}
