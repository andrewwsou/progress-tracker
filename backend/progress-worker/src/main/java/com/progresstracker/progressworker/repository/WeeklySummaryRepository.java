package com.progresstracker.progressworker.repository;

import com.progresstracker.progressworker.model.WeeklySummary;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface WeeklySummaryRepository extends JpaRepository<WeeklySummary, Long> {

    /**
     * Locks the oldest summaries that are waiting, or whose claim has expired, for the current
     * transaction. SKIP LOCKED lets several workers claim different rows at the same moment
     * instead of queueing behind one another.
     */
    @Query(value = """
            select * from weekly_summaries
            where (status = 'PENDING' or (status = 'IN_PROGRESS' and lease_until < :now))
              and attempts < :maxAttempts
            order by created_at, id
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<WeeklySummary> lockClaimable(@Param("now") OffsetDateTime now,
                                      @Param("maxAttempts") int maxAttempts,
                                      @Param("limit") int limit);

    /**
     * Gives up on summaries that have used every attempt: their last claim expired, or they are
     * waiting but the attempt limit has since been lowered.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update WeeklySummary s set s.status = :failed, s.leaseUntil = null, s.lastError = :reason
            where s.attempts >= :maxAttempts
              and (s.status = :pending or (s.status = :inProgress and s.leaseUntil < :now))
            """)
    int failExhausted(@Param("failed") WeeklySummary.Status failed,
                      @Param("pending") WeeklySummary.Status pending,
                      @Param("inProgress") WeeklySummary.Status inProgress,
                      @Param("now") OffsetDateTime now,
                      @Param("maxAttempts") int maxAttempts,
                      @Param("reason") String reason);

    /** Extends a claim's lease, only if the claim is still the current one. Returns the rows updated. */
    @Modifying
    @Query("""
            update WeeklySummary s set s.leaseUntil = :leaseUntil
            where s.id = :id and s.status = :inProgress and s.attempts = :attempt
            """)
    int renewLease(@Param("id") long id,
                   @Param("attempt") int attempt,
                   @Param("inProgress") WeeklySummary.Status inProgress,
                   @Param("leaseUntil") OffsetDateTime leaseUntil);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from WeeklySummary s where s.id = :id")
    Optional<WeeklySummary> findByIdForUpdate(@Param("id") Long id);

    /** Tokens spent on summaries finished since the given time, whichever source wrote the final text. */
    @Query("""
            select coalesce(sum(coalesce(s.inputTokens, 0) + coalesce(s.outputTokens, 0)), 0)
            from WeeklySummary s
            where s.completedAt >= :since
            """)
    long tokensSpentSince(@Param("since") OffsetDateTime since);
}
