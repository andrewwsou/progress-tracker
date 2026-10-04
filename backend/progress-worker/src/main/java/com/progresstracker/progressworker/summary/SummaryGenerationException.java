package com.progresstracker.progressworker.summary;

/**
 * The model could not produce a usable summary. {@link #reason()} is a short code that is stored
 * with the template summary written instead. {@link #usage()} is set when tokens were spent
 * anyway (a refusal or a truncated answer), so the daily budget still counts them.
 */
public class SummaryGenerationException extends RuntimeException {

    private final String reason;
    private final AiUsage usage;

    public SummaryGenerationException(String reason, Throwable cause, AiUsage usage) {
        super(reason, cause);
        this.reason = reason;
        this.usage = usage;
    }

    public String reason() {
        return reason;
    }

    public AiUsage usage() {
        return usage;
    }
}
