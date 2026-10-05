package com.progresstracker.progressworker.repository;

import com.progresstracker.progressworker.model.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /**
     * Claims an event for the current transaction.
     *
     * If another transaction is handling the same event right now, this waits for it: when that
     * one commits, the row exists and this returns 0; when it rolls back, this inserts and returns 1.
     * So exactly one transaction ever gets to apply an event.
     *
     * @return 1 if this transaction now owns the event, 0 if it was already processed
     */
    @Modifying
    @Query(value = """
            insert into processed_events (event_id, occurred_at, processed_at)
            values (:eventId, cast(:occurredAt as timestamptz), now())
            on conflict (event_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("eventId") UUID eventId, @Param("occurredAt") OffsetDateTime occurredAt);

    /**
     * Deletes up to {@code limit} events handled before the cutoff. Rows another worker's purge has
     * locked are skipped, so two workers purging at once do not wait on each other.
     *
     * @return how many rows were deleted
     */
    @Modifying
    @Query(value = """
            delete from processed_events
            where event_id in (
                select event_id from processed_events
                where processed_at < :cutoff
                limit :limit
                for update skip locked
            )
            """, nativeQuery = true)
    int deleteProcessedBefore(@Param("cutoff") OffsetDateTime cutoff, @Param("limit") int limit);
}
