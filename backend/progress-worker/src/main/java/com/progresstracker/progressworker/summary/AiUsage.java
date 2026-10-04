package com.progresstracker.progressworker.summary;

/** One model call: which model answered, the tokens it used, and how long it took. */
public record AiUsage(String model, long inputTokens, long outputTokens, long latencyMs) {
}
