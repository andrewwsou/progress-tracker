package com.progresstracker.progressworker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * What the worker has allowed itself to spend on model calls in one UTC day, across every worker.
 * A call is counted here before it is made, and never refunded, so a timeout or an error still
 * counts. See {@code TokenBudget}.
 */
@Entity
@Table(name = "llm_daily_usage")
public class LlmDailyUsage {

    @Id
    @Column(name = "usage_date")
    private LocalDate usageDate;

    /** API requests reserved, retries included. */
    @Column(nullable = false)
    private int calls;

    /** An upper bound on the tokens those requests can use: prompt size plus the output cap. */
    @Column(name = "reserved_tokens", nullable = false)
    private long reservedTokens;

    protected LlmDailyUsage() {
    }

    public LocalDate getUsageDate() {
        return usageDate;
    }

    public int getCalls() {
        return calls;
    }

    public long getReservedTokens() {
        return reservedTokens;
    }
}
