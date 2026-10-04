package com.progresstracker.progressworker.summary;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateSummaryWriterTest {

    private final TemplateSummaryWriter writer = new TemplateSummaryWriter();

    @Test
    void summarizesTheWeekFromTheNumbers() {
        SummaryOutput summary = writer.write(WeekStatsFixtures.twoHabits());

        assertThat(summary.headline()).isEqualTo("4 completions and 46 XP this week");
        assertThat(summary.body()).isEqualTo(
                "Your most consistent habit was Read with 3 completions. Your longest streak is 9 days on Read. "
                        + "Next week, give Run 5k a little more attention.");
        assertThat(summary.focusHabit()).isEqualTo("Run 5k");
    }

    @Test
    void usesSingularWordsForOneAndWeeksForWeeklyHabits() {
        SummaryOutput summary = writer.write(WeekStatsFixtures.habits(
                new WeekStats.HabitWeek("Plan the week", "WEEKLY", 1, 10, 3, 3)));

        assertThat(summary.headline()).isEqualTo("1 completion and 10 XP this week");
        assertThat(summary.body()).isEqualTo(
                "Your most consistent habit was Plan the week with 1 completion. "
                        + "Your longest streak is 3 weeks on Plan the week.");
        assertThat(summary.focusHabit()).isEqualTo("Plan the week");
    }

    @Test
    void stillWritesSomethingWhenTheHabitsAreGone() {
        SummaryOutput summary = writer.write(WeekStatsFixtures.habits());

        assertThat(summary.headline()).isEqualTo("No habits to summarize this week");
        assertThat(summary.focusHabit()).isNull();
    }

    @Test
    void theTemplatePassesTheSameValidationAsTheModel() {
        WeekStats week = WeekStatsFixtures.twoHabits();

        assertThat(new SummaryValidator().findProblem(writer.write(week), week)).isEmpty();
    }
}
