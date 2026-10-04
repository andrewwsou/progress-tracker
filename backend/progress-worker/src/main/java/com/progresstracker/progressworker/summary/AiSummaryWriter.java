package com.progresstracker.progressworker.summary;

/** Writes a summary with a language model. */
public interface AiSummaryWriter {

    /** False unless the model is switched on, a key is set, and the model is an allowed one. */
    boolean isEnabled();

    /** Size of the prompt for this week in UTF-8 bytes, an upper bound on its input tokens. */
    long promptBytes(WeekStats stats);

    /** @throws SummaryGenerationException when the model's answer cannot be used */
    AiSummary write(WeekStats stats);
}
