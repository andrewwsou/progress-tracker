package com.progresstracker.progressworker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One user's summary of one week. The API creates the row as PENDING when the weekly job asks
 * for summaries; the worker claims it, writes the text, and marks it READY. The API keeps its
 * own copy of this entity to read finished summaries; both describe the same table.
 */
@Entity
@Table(
        name = "weekly_summaries",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_weekly_summaries_user_week", columnNames = {"user_id", "week_start"}),
        indexes = {
                @Index(name = "idx_weekly_summaries_status", columnList = "status, created_at"),
                @Index(name = "idx_weekly_summaries_completed_at", columnList = "completed_at")
        }
)
public class WeeklySummary {

    public enum Status { PENDING, IN_PROGRESS, READY, FAILED }

    /** Who wrote the text: the model, or the fallback template. */
    public enum Source { AI, TEMPLATE }

    /** The finished text and how it was produced. */
    public record Result(
            int completions,
            int xpEarned,
            String headline,
            String body,
            String focusHabit,
            Source source,
            String fallbackReason,
            String model,
            Integer inputTokens,
            Integer outputTokens,
            Integer latencyMs
    ) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    /** How many times a worker has claimed this row. Also the fencing token for finishing it. */
    @Column(nullable = false)
    private int attempts;

    /** The current claim expires at this time; after it, another worker may take the row over. */
    @Column(name = "lease_until")
    private OffsetDateTime leaseUntil;

    private Integer completions;

    @Column(name = "xp_earned")
    private Integer xpEarned;

    @Column(length = 120)
    private String headline;

    @Column(length = 600)
    private String body;

    @Column(name = "focus_habit")
    private String focusHabit;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Source source;

    /** Why the template wrote the text instead of the model, when it did. */
    @Column(name = "fallback_reason", length = 300)
    private String fallbackReason;

    /** The last error while processing this row, if an attempt failed. */
    @Column(name = "last_error", length = 300)
    private String lastError;

    /** The model that wrote the text, as reported by the API (it can differ after a refusal fallback). */
    @Column(length = 100)
    private String model;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    protected WeeklySummary() {
    }

    public void claim(OffsetDateTime leaseUntil) {
        this.status = Status.IN_PROGRESS;
        this.attempts++;
        this.leaseUntil = leaseUntil;
    }

    public void markReady(Result result, OffsetDateTime at) {
        this.status = Status.READY;
        this.leaseUntil = null;
        this.completions = result.completions();
        this.xpEarned = result.xpEarned();
        this.headline = result.headline();
        this.body = result.body();
        this.focusHabit = result.focusHabit();
        this.source = result.source();
        this.fallbackReason = result.fallbackReason();
        this.model = result.model();
        this.inputTokens = result.inputTokens();
        this.outputTokens = result.outputTokens();
        this.latencyMs = result.latencyMs();
        this.completedAt = at;
    }

    /** Hands the row back after a failed attempt: to be retried, or given up on for good. */
    public void release(boolean giveUp, String error) {
        this.status = giveUp ? Status.FAILED : Status.PENDING;
        this.leaseUntil = null;
        this.lastError = error;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public LocalDate getWeekStart() {
        return weekStart;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public OffsetDateTime getLeaseUntil() {
        return leaseUntil;
    }

    public String getHeadline() {
        return headline;
    }

    public Source getSource() {
        return source;
    }
}
