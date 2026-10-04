package com.progresstracker.progresstracker.model;

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
 * One user's summary of one week. This service creates the row as PENDING when the weekly job
 * runs and reads it back once the worker has written it; the worker owns everything in between.
 * The worker keeps its own copy of this entity; both describe the same table.
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

    /** Who wrote the text: Claude, or the fallback template. */
    public enum Source { AI, TEMPLATE }

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

    @Column(nullable = false)
    private int attempts;

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

    @Column(name = "fallback_reason", length = 300)
    private String fallbackReason;

    @Column(name = "last_error", length = 300)
    private String lastError;

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

    public Long getId() {
        return id;
    }

    public LocalDate getWeekStart() {
        return weekStart;
    }

    public Status getStatus() {
        return status;
    }

    public Integer getCompletions() {
        return completions;
    }

    public Integer getXpEarned() {
        return xpEarned;
    }

    public String getHeadline() {
        return headline;
    }

    public String getBody() {
        return body;
    }

    public String getFocusHabit() {
        return focusHabit;
    }

    public Source getSource() {
        return source;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }
}
