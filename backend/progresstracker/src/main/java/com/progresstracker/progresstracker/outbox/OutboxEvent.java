package com.progresstracker.progresstracker.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An event waiting to be published to the queue. It is written in the same database
 * transaction as the change it describes, so the two can never disagree: either both commit
 * or neither does. A separate relay publishes it afterwards (the transactional outbox pattern).
 */
@Entity
@Table(
        name = "outbox_events",
        indexes = @Index(name = "idx_outbox_events_unpublished", columnList = "published_at, created_at")
)
public class OutboxEvent implements Persistable<UUID> {

    /** Also the event id that consumers use to recognise a duplicate delivery. */
    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String type;

    /** The exact JSON body sent to the queue. */
    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Null until the queue has accepted the event. */
    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    // The id is assigned here rather than by the database, so Spring Data cannot tell a new row
    // from an existing one by looking at it. This flag tells it to INSERT without a SELECT first.
    @Transient
    private boolean isNew = true;

    protected OutboxEvent() {
    }

    public OutboxEvent(UUID id, String type, String payload, OffsetDateTime createdAt) {
        this.id = id;
        this.type = type;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public void markPublished(OffsetDateTime at) {
        this.publishedAt = at;
    }
}
