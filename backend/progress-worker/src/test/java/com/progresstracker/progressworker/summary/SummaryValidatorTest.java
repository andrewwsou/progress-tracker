package com.progresstracker.progressworker.summary;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryValidatorTest {

    private final SummaryValidator validator = new SummaryValidator();
    private final WeekStats week = WeekStatsFixtures.twoHabits();

    private static SummaryOutput summary(String headline, String body, String focusHabit) {
        return new SummaryOutput(headline, body, focusHabit);
    }

    @Test
    void acceptsASummaryThatOnlyUsesTheData() {
        SummaryOutput grounded = summary(
                "Read carried your week",
                "You completed Read 3 times and Run 5k 1 time, earning 46 XP over 7 days. "
                        + "Your longest Read streak is 9 days.",
                "Run 5k");

        assertThat(validator.findProblem(grounded, week)).isEmpty();
    }

    @Test
    void acceptsTheDatesOfTheWeekAndDigitsInHabitNames() {
        SummaryOutput withDates = summary(
                "Your week of September 21",
                "From 21 to 27 September 2026 you kept up Read, and Run 5k got started.",
                "Read");

        assertThat(validator.findProblem(withDates, week)).isEmpty();
    }

    @Test
    void rejectsANumberThatIsNotInTheData() {
        SummaryOutput invented = summary("A strong week", "You completed 12 habits this week.", "Read");

        assertThat(validator.findProblem(invented, week)).contains("mentions 12, which is not in the data");
    }

    @Test
    void rejectsANumberWrittenAsAWord() {
        // Neither number is in the data, and in words the digit check alone would miss both.
        SummaryOutput inWords = summary("Strong week",
                "You kept up Swimming and finished Read three times, a twelve-day best.", "Run 5k");

        assertThat(validator.findProblem(inWords, week)).contains("writes the number \"three\" as a word");
        assertThat(validator.findProblem(summary("Twenty minutes a day", "Keep going.", "Read"), week))
                .contains("writes the number \"Twenty\" as a word");
        assertThat(validator.findProblem(summary("A good week", "Read is on a dozen-day run.", "Read"), week))
                .contains("writes the number \"dozen\" as a word");
    }

    @Test
    void rejectsCountsAndOrdinalsWrittenAsWords() {
        // Neither statistic is in the data (Read: 3 completions, a 3-day streak).
        assertThat(validator.findProblem(summary("Steady week",
                "You did Read twice and hit your third straight week.", "Run 5k"), week))
                .contains("writes the number \"twice\" as a word");
        assertThat(validator.findProblem(summary("Steady week", "Your third straight week of Read.", "Run 5k"), week))
                .contains("writes the number \"third\" as a word");
        for (String word : List.of("once", "Thrice", "twelfth", "twentieth", "fortieth", "hundredth", "thousandth")) {
            assertThat(validator.findProblem(summary("Steady week", "Read, " + word + " this week.", "Read"), week))
                    .as(word)
                    .contains("writes the number \"" + word + "\" as a word");
        }
    }

    @Test
    void oneIsRejectedEvenAsAPronoun() {
        // It cannot be told apart from a count ("one completion"), so the prompt asks for neither.
        assertThat(validator.findProblem(summary("Steady week",
                "Read was one of your strongest habits this week.", "Run 5k"), week))
                .contains("writes the number \"one\" as a word");
    }

    @Test
    void firstAndSecondAreAllowedBecauseTheyAreRarelyCounts() {
        assertThat(validator.findProblem(summary("Your first full week",
                "Read got a second wind, and Run 5k got started.", "Run 5k"), week)).isEmpty();
    }

    @Test
    void twiceInsideAHabitNameIsTheUsersOwnText() {
        WeekStats flossing = WeekStatsFixtures.habits(
                new WeekStats.HabitWeek("Floss twice", "DAILY", 3, 36, 3, 9),
                new WeekStats.HabitWeek("Read", "DAILY", 1, 10, 1, 4));

        assertThat(validator.findProblem(summary("Floss twice led the week",
                "You did Floss twice 3 times.", "Read"), flossing)).isEmpty();
    }

    @Test
    void onlyWholeNumberWordsAreRejected() {
        SummaryOutput noNumbers = summary("Someone is reading often",
                "Everyone needs a rest, but Read was never left alone this week.", "Read");

        assertThat(validator.findProblem(noNumbers, week)).isEmpty();
    }

    @Test
    void aNumberWordInsideAHabitNameIsTheUsersOwnText() {
        WeekStats water = WeekStatsFixtures.habits(
                new WeekStats.HabitWeek("Drink eight glasses", "DAILY", 3, 36, 3, 9),
                new WeekStats.HabitWeek("Read", "DAILY", 1, 10, 1, 4));

        assertThat(validator.findProblem(summary("Drink eight glasses led the week",
                "You did Drink eight glasses 3 times.", "Read"), water)).isEmpty();
        // The rest of the text is still checked.
        assertThat(validator.findProblem(summary("Drink eight glasses led the week",
                "You did Drink eight glasses three times.", "Read"), water))
                .contains("writes the number \"three\" as a word");
    }

    @Test
    void aHabitNameIsOnlyLeftOutWhereItStandsAsWholeWords() {
        // A one-letter name must not hide the letters of a number word.
        WeekStats shortName = WeekStatsFixtures.habits(
                new WeekStats.HabitWeek("e", "DAILY", 3, 36, 3, 9),
                new WeekStats.HabitWeek("Read", "DAILY", 1, 10, 1, 4));
        assertThat(validator.findProblem(summary("A good week",
                "Read three times, a twelve-day best.", "Read"), shortName))
                .contains("writes the number \"three\" as a word");

        // Taking a name out must not split an ordinary word into a number word.
        WeekStats prefixNames = WeekStatsFixtures.habits(
                new WeekStats.HabitWeek("Every", "DAILY", 3, 36, 3, 9),
                new WeekStats.HabitWeek("Of", "DAILY", 1, 10, 1, 4));
        assertThat(validator.findProblem(summary("Everyone needs a rest",
                "Often the hardest part is starting, and Every kept going.", "Every"), prefixNames)).isEmpty();
    }

    @Test
    void acceptsThousandsSeparatorsForNumbersInTheData() {
        WeekStats bigWeek = WeekStatsFixtures.habits(new WeekStats.HabitWeek("Read", "DAILY", 7, 1250, 40, 40));

        assertThat(validator.findProblem(summary("1,250 XP this week", "Read every day.", "Read"), bigWeek)).isEmpty();
    }

    @Test
    void rejectsAFocusHabitTheUserDoesNotHave() {
        assertThat(validator.findProblem(summary("A good week", "Keep going.", "Swimming"), week))
                .contains("focusHabit is not one of the user's habits");
        // Exact match only: a near miss would show the user a habit name they never typed.
        assertThat(validator.findProblem(summary("A good week", "Keep going.", "read"), week)).isPresent();
        assertThat(validator.findProblem(summary("A good week", "Keep going.", null), week)).isPresent();
    }

    @Test
    void rejectsMissingOrOverlongText() {
        assertThat(validator.findProblem(summary(" ", "Keep going.", "Read"), week)).contains("headline is empty");
        assertThat(validator.findProblem(summary("A good week", "", "Read"), week)).contains("body is empty");
        assertThat(validator.findProblem(summary("x".repeat(101), "Keep going.", "Read"), week))
                .contains("headline is 101 characters");
        assertThat(validator.findProblem(summary("A good week", "x".repeat(501), "Read"), week))
                .contains("body is 501 characters");
    }
}
