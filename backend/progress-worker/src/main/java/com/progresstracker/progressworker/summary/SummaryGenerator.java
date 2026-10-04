package com.progresstracker.progressworker.summary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Decides who writes a summary. The model writes it when it can; the template writes it when
 * Claude is off, when the week is too big to send, when a daily cap is reached, or when the
 * model's answer fails (an error, a refusal, a truncated answer, a validation problem, or
 * anything unexpected). Every check that can stop a call runs before the call.
 */
@Component
public class SummaryGenerator {

    private static final Logger log = LoggerFactory.getLogger(SummaryGenerator.class);

    private final AiSummaryWriter aiWriter;
    private final TemplateSummaryWriter templateWriter;
    private final SummaryValidator validator;
    private final TokenBudget tokenBudget;
    private final SummaryProperties.Llm config;

    public SummaryGenerator(AiSummaryWriter aiWriter,
                            TemplateSummaryWriter templateWriter,
                            SummaryValidator validator,
                            TokenBudget tokenBudget,
                            SummaryProperties properties) {
        this.aiWriter = aiWriter;
        this.templateWriter = templateWriter;
        this.validator = validator;
        this.tokenBudget = tokenBudget;
        this.config = properties.llm();
    }

    public GeneratedSummary generate(WeekStats stats) {
        if (stats.habits().isEmpty()) {
            return template(stats, "NO_HABITS", null);
        }
        if (!aiWriter.isEnabled()) {
            return template(stats, "LLM_DISABLED", null);
        }
        if (stats.habits().size() > config.maxHabits()) {
            return template(stats, "TOO_MANY_HABITS", null);
        }
        long promptBytes = aiWriter.promptBytes(stats);
        if (promptBytes > config.maxPromptBytes()) {
            return template(stats, "PROMPT_TOO_LARGE", null);
        }
        if (tokenBudget.isExhausted()) {
            return template(stats, "DAILY_TOKEN_BUDGET_SPENT", null);
        }
        // Outside the try below on purpose: if the reservation itself fails, no call is made and
        // the summary is retried later.
        if (!tokenBudget.tryReserve(promptBytes)) {
            return template(stats, "DAILY_LLM_CAP_REACHED", null);
        }

        AiSummary summary;
        Optional<String> problem;
        try {
            summary = aiWriter.write(stats);
            problem = validator.findProblem(summary.output(), stats);
        } catch (SummaryGenerationException e) {
            log.warn("Claude did not write the summary for user {} ({}); using the template",
                    stats.userId(), e.reason(), e.getCause());
            return template(stats, e.reason(), e.usage());
        } catch (RuntimeException e) {
            // A bug or an unexpected response must not cost the user their summary.
            log.error("Unexpected error writing the summary for user {}; using the template", stats.userId(), e);
            return template(stats, "UNEXPECTED_" + e.getClass().getSimpleName(), null);
        }
        if (problem.isPresent()) {
            return template(stats, "VALIDATION: " + problem.get(), summary.usage());
        }
        return GeneratedSummary.fromModel(summary);
    }

    private GeneratedSummary template(WeekStats stats, String reason, AiUsage usage) {
        return GeneratedSummary.fromTemplate(templateWriter.write(stats), reason, usage);
    }
}
