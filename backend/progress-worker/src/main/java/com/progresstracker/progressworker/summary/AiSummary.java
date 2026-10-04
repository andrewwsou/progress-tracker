package com.progresstracker.progressworker.summary;

/** A summary the model wrote, with what it cost. */
public record AiSummary(SummaryOutput output, AiUsage usage) {
}
