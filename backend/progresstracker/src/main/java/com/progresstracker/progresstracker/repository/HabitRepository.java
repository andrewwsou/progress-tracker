package com.progresstracker.progresstracker.repository;

import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface HabitRepository extends JpaRepository<Habit, Long> {
    List<Habit> findByUser(User user);

    @Query("select coalesce(sum(h.xpTotal), 0) from Habit h where h.user = :user")
    long sumXpByUser(@Param("user") User user);

    /**
     * Locks the habit's row until the current transaction ends. FOR NO KEY UPDATE blocks anything
     * else that writes the row (the nightly reset, an edit, a delete) but not inserts of rows that
     * only reference it.
     *
     * @return the id, or empty if the habit no longer exists
     */
    @Query(value = "select id from habit where id = :id for no key update", nativeQuery = true)
    Optional<Long> lockById(@Param("id") Long id);

    /**
     * Sets the current streak to zero for every habit whose streak has lapsed, judged on its
     * owner's calendar at the instant {@code now}: a daily habit last completed before the owner's
     * yesterday, or a weekly one last completed before the Monday of the owner's previous week.
     * Habits with no streak, or never completed, are left alone. One statement for every user and
     * time zone: PostgreSQL works out each owner's local date.
     *
     * @return how many habits were updated
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update habit h set current_streak = 0
            from app_user u
            where u.id = h.user_id
              and h.current_streak > 0
              and h.last_completed_date is not null
              and ((h.frequency = 'WEEKLY'
                    and h.last_completed_date < cast(date_trunc('week', cast(:now as timestamptz) at time zone u.time_zone) as date) - 7)
                or ((h.frequency is null or h.frequency <> 'WEEKLY')
                    and h.last_completed_date < cast(cast(:now as timestamptz) at time zone u.time_zone as date) - 1))
            """, nativeQuery = true)
    int resetLapsedStreaks(@Param("now") OffsetDateTime now);

}
