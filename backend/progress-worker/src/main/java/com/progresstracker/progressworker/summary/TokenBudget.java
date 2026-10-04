package com.progresstracker.progressworker.summary;

import com.progresstracker.progressworker.repository.LlmDailyUsageRepository;
import com.progresstracker.progressworker.repository.WeeklySummaryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * The spending caps on model calls, per UTC day and across every worker, kept in the database so
 * they hold across restarts.
 *
 * <ul>
 *   <li>{@link #tryReserve} is the hard cap. Before each call it reserves the most the call could
 *       use: every request it may send (retries included) and, per request, the prompt plus the
 *       output cap (twice that when a refused request may be re-run on a fallback model). A
 *       reservation commits before the call and is never refunded, so timeouts and errors count.</li>
 *   <li>{@link #isExhausted} is a second check on what finished summaries actually recorded.</li>
 * </ul>
 */
@Component
public class TokenBudget {

    /** Room for what the API adds around the prompt (the output schema and message framing). */
    static final long REQUEST_OVERHEAD_TOKENS = 1_000;

    private final WeeklySummaryRepository summaries;
    private final LlmDailyUsageRepository usage;
    private final SummaryProperties.Llm config;

    public TokenBudget(WeeklySummaryRepository summaries, LlmDailyUsageRepository usage, SummaryProperties properties) {
        this.summaries = summaries;
        this.usage = usage;
        this.config = properties.llm();
    }

    @Transactional(readOnly = true)
    public boolean isExhausted() {
        OffsetDateTime startOfToday = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);
        return summaries.tokensSpentSince(startOfToday) >= config.dailyTokenBudget();
    }

    /**
     * Reserves one summary's worst case against today's caps.
     *
     * @param promptBytes size of the prompt in UTF-8 bytes (no more tokens than that)
     * @return true if the call may be made, false if it would pass a cap
     */
    @Transactional
    public boolean tryReserve(long promptBytes) {
        int requests = config.maxRetries() + 1;
        long tokensPerRequest = (promptBytes + REQUEST_OVERHEAD_TOKENS + config.maxOutputTokens())
                * (config.refusalFallbacks() ? 2 : 1);
        return usage.reserve(LocalDate.now(ZoneOffset.UTC), requests, requests * tokensPerRequest,
                config.maxCallsPerDay(), config.dailyTokenBudget()) == 1;
    }
}
