package com.progresstracker.progresstracker.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims the oldest unpublished events for the current transaction. SKIP LOCKED means a
     * second relay (another API instance) passes over rows this one holds instead of waiting
     * for them, so two relays never publish the same row at the same time.
     */
    // Every matching row has published_at NULL, so ordering by it first changes nothing about the
    // result. It is there because PostgreSQL only reads rows in index order when the ORDER BY
    // matches the index columns (published_at, created_at); without it each batch would sort the
    // whole backlog.
    @Query(value = """
            select * from outbox_events
            where published_at is null
            order by published_at, created_at
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> lockNextUnpublished(@Param("limit") int limit);

    @Modifying
    @Query("delete from OutboxEvent e where e.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") OffsetDateTime cutoff);
}
