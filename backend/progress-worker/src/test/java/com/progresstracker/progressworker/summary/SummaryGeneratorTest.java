package com.progresstracker.progressworker.summary;

import com.progresstracker.progressworker.model.WeeklySummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Who writes the summary in each situation. */
@ExtendWith(MockitoExtension.class)
class SummaryGeneratorTest {

    private static final AiUsage USAGE = new AiUsage("claude-opus-5-5", 412, 96, 1800);

    @Mock
    private AiSummaryWriter aiWriter;

    @Mock
    private TokenBudget tokenBudget;

    private SummaryGenerator generator;
    private final WeekStats week = WeekStatsFixtures.twoHabits();

    @BeforeEach
    void setUp() {
        SummaryProperties properties = new SummaryProperties(
                new SummaryProperties.Llm(true, "test-key", "claude-opus-5-5", "low", 2048, Duration.ofSeconds(30), 0,
                        null, 100_000, true, 10, 20, 8000),
                new SummaryProperties.Job(5, Duration.ofMinutes(5), 3));
        generator = new SummaryGenerator(aiWriter, new TemplateSummaryWriter(), new SummaryValidator(), tokenBudget,
                properties);
    }

    @Test
    void theModelWritesAValidSummary() {
        when(tokenBudget.tryReserve(anyLong())).thenReturn(true);
        SummaryOutput fromModel = new SummaryOutput("Read carried your week", "You completed Read 3 times.", "Run 5k");
        when(aiWriter.isEnabled()).thenReturn(true);
        when(aiWriter.write(week)).thenReturn(new AiSummary(fromModel, USAGE));

        GeneratedSummary summary = generator.generate(week);

        assertThat(summary.source()).isEqualTo(WeeklySummary.Source.AI);
        assertThat(summary.output()).isEqualTo(fromModel);
        assertThat(summary.usage()).isEqualTo(USAGE);
        assertThat(summary.fallbackReason()).isNull();
    }

    @Test
    void withClaudeOffTheTemplateWritesItAndNoCallIsMade() {
        when(aiWriter.isEnabled()).thenReturn(false);

        GeneratedSummary summary = generator.generate(week);

        assertThat(summary.source()).isEqualTo(WeeklySummary.Source.TEMPLATE);
        assertThat(summary.fallbackReason()).isEqualTo("LLM_DISABLED");
        verify(aiWriter, never()).write(any());
        verify(tokenBudget, never()).tryReserve(anyLong());
    }

    @Test
    void onceTheDailyCapIsReachedTheTemplateWritesItAndNoCallIsMade() {
        when(aiWriter.isEnabled()).thenReturn(true);
        when(tokenBudget.tryReserve(anyLong())).thenReturn(false);

        GeneratedSummary summary = generator.generate(week);

        assertThat(summary.fallbackReason()).isEqualTo("DAILY_LLM_CAP_REACHED");
        verify(aiWriter, never()).write(any());
    }

    @Test
    void theCallIsReservedBeforeItIsMade() {
        when(aiWriter.isEnabled()).thenReturn(true);
        when(aiWriter.promptBytes(week)).thenReturn(1234L);
        when(tokenBudget.tryReserve(1234L)).thenReturn(true);
        when(aiWriter.write(week)).thenReturn(new AiSummary(
                new SummaryOutput("Read carried your week", "You completed Read 3 times.", "Run 5k"), USAGE));

        generator.generate(week);

        InOrder order = inOrder(tokenBudget, aiWriter);
        order.verify(tokenBudget).tryReserve(1234L);
        order.verify(aiWriter).write(week);
    }

    @Test
    void aWeekWithTooManyHabitsIsNeverSent() {
        when(aiWriter.isEnabled()).thenReturn(true);
        WeekStats bigWeek = WeekStatsFixtures.habits(IntStream.range(0, 21)
                .mapToObj(i -> new WeekStats.HabitWeek("Habit " + i, "DAILY", 1, 10, 1, 1))
                .toArray(WeekStats.HabitWeek[]::new));

        GeneratedSummary summary = generator.generate(bigWeek);

        assertThat(summary.fallbackReason()).isEqualTo("TOO_MANY_HABITS");
        verify(tokenBudget, never()).tryReserve(anyLong());
        verify(aiWriter, never()).write(any());
    }

    @Test
    void aPromptOverTheSizeCapIsNeverSent() {
        when(aiWriter.isEnabled()).thenReturn(true);
        when(aiWriter.promptBytes(week)).thenReturn(8001L);

        GeneratedSummary summary = generator.generate(week);

        assertThat(summary.fallbackReason()).isEqualTo("PROMPT_TOO_LARGE");
        verify(tokenBudget, never()).tryReserve(anyLong());
        verify(aiWriter, never()).write(any());
    }

    @Test
    void onceTodaysBudgetIsSpentTheTemplateWritesItAndNoCallIsMade() {
        when(aiWriter.isEnabled()).thenReturn(true);
        when(tokenBudget.isExhausted()).thenReturn(true);

        GeneratedSummary summary = generator.generate(week);

        assertThat(summary.fallbackReason()).isEqualTo("DAILY_TOKEN_BUDGET_SPENT");
        verify(aiWriter, never()).write(any());
    }

    @Test
    void aFailedCallFallsBackToTheTemplateAndKeepsTheTokensItSpent() {
        when(tokenBudget.tryReserve(anyLong())).thenReturn(true);
        when(aiWriter.isEnabled()).thenReturn(true);
        when(aiWriter.write(week)).thenThrow(new SummaryGenerationException("REFUSED", null, USAGE));

        GeneratedSummary summary = generator.generate(week);

        assertThat(summary.source()).isEqualTo(WeeklySummary.Source.TEMPLATE);
        assertThat(summary.fallbackReason()).isEqualTo("REFUSED");
        assertThat(summary.usage()).isEqualTo(USAGE); // still counted against the daily budget
        assertThat(summary.output().headline()).isEqualTo("4 completions and 46 XP this week");
    }

    @Test
    void anUnexpectedErrorStillEndsInTheTemplate() {
        when(tokenBudget.tryReserve(anyLong())).thenReturn(true);
        when(aiWriter.isEnabled()).thenReturn(true);
        when(aiWriter.write(week)).thenThrow(new IllegalStateException("a bug"));

        GeneratedSummary summary = generator.generate(week);

        assertThat(summary.source()).isEqualTo(WeeklySummary.Source.TEMPLATE);
        assertThat(summary.fallbackReason()).isEqualTo("UNEXPECTED_IllegalStateException");
    }

    @Test
    void anAnswerThatFailsValidationIsReplacedByTheTemplate() {
        when(tokenBudget.tryReserve(anyLong())).thenReturn(true);
        when(aiWriter.isEnabled()).thenReturn(true);
        when(aiWriter.write(week)).thenReturn(new AiSummary(
                new SummaryOutput("A great week", "You completed 12 habits.", "Read"), USAGE));

        GeneratedSummary summary = generator.generate(week);

        assertThat(summary.source()).isEqualTo(WeeklySummary.Source.TEMPLATE);
        assertThat(summary.fallbackReason()).isEqualTo("VALIDATION: mentions 12, which is not in the data");
        assertThat(summary.usage()).isEqualTo(USAGE);
    }

    @Test
    void aWeekWithNoHabitsLeftNeverReachesTheModel() {
        GeneratedSummary summary = generator.generate(WeekStatsFixtures.habits());

        assertThat(summary.fallbackReason()).isEqualTo("NO_HABITS");
        verify(aiWriter, never()).write(any());
    }
}
