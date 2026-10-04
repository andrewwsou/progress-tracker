package com.progresstracker.progressworker.summary;

import com.anthropic.models.beta.messages.BetaFallbacksParam;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The request the writer builds. What goes over the wire is checked against WireMock in WeeklySummaryJobIT. */
class ClaudeSummaryWriterTest {

    private static ClaudeSummaryWriter writer(String apiKey, boolean refusalFallbacks) {
        return writer(true, apiKey, "claude-opus-5-5", refusalFallbacks);
    }

    private static ClaudeSummaryWriter writer(boolean enabled, String apiKey, String model, boolean refusalFallbacks) {
        SummaryProperties properties = new SummaryProperties(
                new SummaryProperties.Llm(enabled, apiKey, model, "low", 2048, Duration.ofSeconds(30), 0,
                        "http://127.0.0.1:9", 100_000, refusalFallbacks, 10, 20, 8000),
                new SummaryProperties.Job(5, Duration.ofMinutes(5), 3));
        return new ClaudeSummaryWriter(properties, new ObjectMapper());
    }

    @Test
    void withoutAnApiKeyItIsDisabledAndRefusesToCall() {
        ClaudeSummaryWriter disabled = writer("", true);

        assertThat(disabled.isEnabled()).isFalse();
        assertThatThrownBy(() -> disabled.write(WeekStatsFixtures.twoHabits()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aKeyAloneDoesNotTurnClaudeOn() {
        ClaudeSummaryWriter keyButNoFlag = writer(false, "test-key", "claude-opus-5-5", true);

        assertThat(keyButNoFlag.isEnabled()).isFalse();
        assertThatThrownBy(() -> keyButNoFlag.write(WeekStatsFixtures.twoHabits()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aModelOffTheAllowListIsNeverCalled() {
        assertThat(writer(true, "test-key", "claude-fable-5-1", true).isEnabled()).isFalse();
    }

    @Test
    void thePromptSizeIsCountedInUtf8Bytes() {
        ClaudeSummaryWriter writer = writer("", true);
        WeekStats week = WeekStatsFixtures.habits(new WeekStats.HabitWeek("Read 📚", "DAILY", 1, 10, 1, 1));

        long bytes = writer.promptBytes(week);

        assertThat(bytes).isEqualTo(ClaudeSummaryWriter.SYSTEM_PROMPT.getBytes(StandardCharsets.UTF_8).length
                + writer.userMessage(week).getBytes(StandardCharsets.UTF_8).length);
        assertThat(bytes).isGreaterThan(ClaudeSummaryWriter.SYSTEM_PROMPT.length() + writer.userMessage(week).length());
    }

    @Test
    void asksForStructuredOutputAtLowEffortWithRefusalFallbacks() {
        ClaudeSummaryWriter writer = writer("test-key", true);
        try {
            MessageCreateParams request = writer.buildRequest(WeekStatsFixtures.twoHabits()).rawParams();

            assertThat(request.model().asString()).isEqualTo("claude-opus-5-5");
            assertThat(request.maxTokens()).isEqualTo(2048);
            BetaOutputConfig output = request.outputConfig().orElseThrow();
            assertThat(output.effort()).contains(BetaOutputConfig.Effort.LOW);
            assertThat(output.format()).isPresent();
            assertThat(request.fallbacks()).map(BetaFallbacksParam::isDefault).contains(true);
            assertThat(request.betas().orElseThrow()).anySatisfy(beta ->
                    assertThat(beta.asString()).isEqualTo(ClaudeSummaryWriter.REFUSAL_FALLBACK_BETA));
        } finally {
            writer.close();
        }
    }

    @Test
    void refusalFallbacksCanBeTurnedOff() {
        ClaudeSummaryWriter writer = writer("test-key", false);
        try {
            MessageCreateParams request = writer.buildRequest(WeekStatsFixtures.twoHabits()).rawParams();

            assertThat(request.fallbacks()).isEmpty();
        } finally {
            writer.close();
        }
    }

    @Test
    void sendsTheWeekAsTaggedJsonWithStreakUnits() {
        ClaudeSummaryWriter writer = writer("", true);

        String message = writer.userMessage(WeekStatsFixtures.twoHabits());

        assertThat(message).startsWith("Here is the user's week. Write their summary.");
        assertThat(message).contains("<week>", "</week>", "\"name\":\"Run 5k\"", "\"completionsThisWeek\":3",
                "\"totalXpEarned\":46", "\"streakUnit\":\"days\"", "\"weekStart\":\"2026-09-21\"");
    }
}
