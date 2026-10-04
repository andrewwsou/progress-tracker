package com.progresstracker.progressworker.summary;

import org.junit.jupiter.api.Test;

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
                "You completed Read 3 times and Run 5k once, earning 46 XP over 7 days. "
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
