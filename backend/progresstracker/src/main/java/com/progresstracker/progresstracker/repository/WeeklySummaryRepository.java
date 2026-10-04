package com.progresstracker.progresstracker.repository;

import com.progresstracker.progresstracker.model.WeeklySummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface WeeklySummaryRepository extends JpaRepository<WeeklySummary, Long> {

    /**
     * Asks for a summary for every user who completed at least one habit in the week, in one
     * statement. Running it twice for the same week adds nothing: the unique key on
     * (user_id, week_start) turns a repeat into a no-op, except that a summary the worker gave up
     * on is queued again with fresh attempts.
     *
     * @return how many summaries were requested or queued again
     */
    @Modifying
    @Query(value = """
            insert into weekly_summaries (user_id, week_start, status, attempts, created_at)
            select u.id, cast(:weekStart as date), 'PENDING', 0, now()
            from app_user u
            where exists (
                select 1 from habit h
                join habit_entries e on e.habit_id = h.id
                where h.user_id = u.id and e.completed_date between :weekStart and :weekEnd)
            on conflict (user_id, week_start) do update
                set status = 'PENDING', attempts = 0, lease_until = null, last_error = null
                where weekly_summaries.status = 'FAILED'
            """, nativeQuery = true)
    int requestForActiveUsers(@Param("weekStart") LocalDate weekStart, @Param("weekEnd") LocalDate weekEnd);

    Optional<WeeklySummary> findFirstByUserIdAndStatusOrderByWeekStartDesc(Long userId, WeeklySummary.Status status);
}
