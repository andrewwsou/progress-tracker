package com.progresstracker.progressworker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row per event the worker has finished handling (the "inbox"). The queue delivers every
 * message at least once, so the same event can arrive again; its id already being here is how
 * the worker knows to ignore the repeat.
 *
 * Rows are deleted once they are older than the queue's retention ({@code ProcessedEventPurger}).
 * A repeat that arrived after its row was gone would still stop at the "already has XP" check in
 * {@code CompletionProcessor}, before any XP or email.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    /** When the API recorded the completion. Null for messages sent before events carried it. */
    @Column(name = "occurred_at")
    private OffsetDateTime occurredAt;

    @Column(name = "processed_at", nullable = false)
    private OffsetDateTime processedAt;

    protected ProcessedEvent() {
    }

    public UUID getEventId() {
        return eventId;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }

    public OffsetDateTime getProcessedAt() {
        return processedAt;
    }
}
