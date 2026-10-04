package com.progresstracker.progressworker.summary;

import com.progresstracker.progressworker.model.WeeklySummary;

/**
 * A finished summary and how it was produced.
 *
 * @param fallbackReason why the template wrote it, or null when the model did
 * @param usage          the model call, if one was made (even when its answer was not used)
 */
public record GeneratedSummary(SummaryOutput output, WeeklySummary.Source source, String fallbackReason, AiUsage usage) {

    static GeneratedSummary fromModel(AiSummary summary) {
        return new GeneratedSummary(summary.output(), WeeklySummary.Source.AI, null, summary.usage());
    }

    static GeneratedSummary fromTemplate(SummaryOutput output, String reason, AiUsage usage) {
        return new GeneratedSummary(output, WeeklySummary.Source.TEMPLATE, reason, usage);
    }
}
