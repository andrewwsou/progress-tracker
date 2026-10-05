package com.progresstracker.progressworker.summary;

import org.springframework.stereotype.Component;

import java.util.Comparator;
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
 *   <li>every number written in digits appears in the data,</li>
 *   <li>no number is written as a word. Rejected, as whole words in any case, unless they are
 *       part of a habit name written out in full: zero to twenty; thirty, forty, fifty, sixty,
 *       seventy, eighty, ninety; hundred, thousand, dozen; once, twice, thrice; the ordinals third
 *       to twentieth; thirtieth, fortieth, fiftieth, sixtieth, seventieth, eightieth, ninetieth,
 *       hundredth, thousandth.</li>
 * </ul>
 * "first" and "second" are allowed: they are usually not counts ("your first week", "a second
 * wind"). "one" is rejected even as a pronoun ("one of your habits"), since it cannot be told
 * apart from a count; the prompt asks for no number words at all. Other habit names mentioned in
 * the body are not checked; only the focus habit has to be one of the user's.
 */
@Component
public class SummaryValidator {

    static final int MAX_HEADLINE_LENGTH = 100;
    static final int MAX_BODY_LENGTH = 500;

    /** Whole numbers, allowing thousands separators: 7, 106, 1,250. */
    private static final Pattern NUMBER = Pattern.compile("\\d{1,3}(?:,\\d{3})+|\\d+");

    /** Numbers written as words, which the digit check above cannot see. Whole words, any case. */
    private static final Pattern NUMBER_WORD = Pattern.compile("\\b(?:zero|one|two|three|four|five|six|seven|eight"
            + "|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty"
            + "|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|dozen"
            + "|once|twice|thrice"
            + "|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth|thirteenth|fourteenth"
            + "|fifteenth|sixteenth|seventeenth|eighteenth|nineteenth|twentieth"
            + "|thirtieth|fortieth|fiftieth|sixtieth|seventieth|eightieth|ninetieth|hundredth|thousandth)\\b",
            Pattern.CASE_INSENSITIVE);

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

        String text = output.headline() + "\n" + output.body();
        Set<Long> allowed = numbersInData(stats);
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            String digits = matcher.group().replace(",", "");
            if (digits.length() > 9 || !allowed.contains(Long.parseLong(digits))) {
                return Optional.of("mentions " + matcher.group() + ", which is not in the data");
            }
        }
        Matcher word = NUMBER_WORD.matcher(withoutHabitNames(text, stats));
        if (word.find()) {
            return Optional.of("writes the number \"" + word.group() + "\" as a word");
        }
        return Optional.empty();
    }

    /**
     * The text with the user's habit names taken out, longest first, so a name like "Drink eight
     * glasses" can still be mentioned. Those words are the user's, not a statistic. A name is only
     * taken out where it stands as whole words: a habit called "e" must not hide the e's in "three",
     * and one called "Every" must not turn "Everyone" into "one".
     */
    private static String withoutHabitNames(String text, WeekStats stats) {
        String rest = text;
        for (String name : stats.habitNames().stream()
                .filter(name -> name != null && !name.isBlank())
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList()) {
            rest = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}])")
                    .matcher(rest)
                    .replaceAll(" ");
        }
        return rest;
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
