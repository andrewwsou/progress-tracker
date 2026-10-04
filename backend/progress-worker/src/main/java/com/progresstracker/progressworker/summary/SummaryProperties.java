package com.progresstracker.progressworker.summary;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Settings under {@code summary.*} in application.yaml. */
@ConfigurationProperties(prefix = "summary")
public record SummaryProperties(@DefaultValue Llm llm, @DefaultValue Job job) {

    /**
     * Every default here is the safe one, so a context that never reads application.yaml still
     * makes no calls.
     *
     * @param enabled           Claude is used only when this is true AND a key is set. A key alone
     *                          (say, one exported in a developer's shell) never turns spending on.
     * @param apiKey            Claude API key
     * @param model             model id; must be one of {@link ClaudeSummaryWriter#ALLOWED_MODELS}
     * @param effort            how much the model thinks before answering (low, medium, high, ...)
     * @param maxOutputTokens   hard cap on one response, thinking included
     * @param timeout           per attempt
     * @param maxRetries        retries after the first attempt; each one counts against the daily caps
     * @param baseUrl           blank for the real API; set to reach a stand-in in tests
     * @param dailyTokenBudget  tokens per UTC day, across all workers, reserved before each call
     * @param refusalFallbacks  re-run a request the model declines on Anthropic's recommended fallback model
     * @param maxCallsPerDay    API requests per UTC day, across all workers, every attempt counted; 0 stops all calls
     * @param maxHabits         a week with more habits than this is written by the template
     * @param maxPromptBytes    a prompt larger than this (UTF-8) is written by the template
     */
    public record Llm(
            @DefaultValue("false") boolean enabled,
            String apiKey,
            @DefaultValue("claude-opus-5-5") String model,
            @DefaultValue("low") String effort,
            @DefaultValue("2048") long maxOutputTokens,
            @DefaultValue("30s") Duration timeout,
            @DefaultValue("0") int maxRetries,
            String baseUrl,
            @DefaultValue("100000") long dailyTokenBudget,
            @DefaultValue("true") boolean refusalFallbacks,
            @DefaultValue("10") int maxCallsPerDay,
            @DefaultValue("20") int maxHabits,
            @DefaultValue("8000") int maxPromptBytes
    ) {
        public Llm {
            // A negative value would turn a cap into no cap (or an SDK default), so refuse to start.
            if (maxRetries < 0 || maxCallsPerDay < 0 || dailyTokenBudget < 0 || maxOutputTokens < 1
                    || maxHabits < 0 || maxPromptBytes < 0) {
                throw new IllegalArgumentException("summary.llm limits must not be negative (max-retries, "
                        + "max-calls-per-day, daily-token-budget, max-habits, max-prompt-bytes; max-output-tokens >= 1)");
            }
        }
    }

    /**
     * @param batchSize   summaries claimed per run
     * @param lease       how long a claim lasts before another worker may take the row over
     * @param maxAttempts claims per summary before it is marked FAILED
     */
    public record Job(
            @DefaultValue("5") int batchSize,
            @DefaultValue("5m") Duration lease,
            @DefaultValue("3") int maxAttempts
    ) {
    }
}
