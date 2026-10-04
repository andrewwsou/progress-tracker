package com.progresstracker.progresstracker.repository;

import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface HabitRepository extends JpaRepository<Habit, Long> {
    List<Habit> findByUser(User user);

    @Query("select coalesce(sum(h.xpTotal), 0) from Habit h where h.user = :user")
    long sumXpByUser(@Param("user") User user);

    /**
     * Sets the current streak to zero for every habit whose streak has lapsed: a daily habit last
     * completed before {@code yesterday}, or a weekly one last completed before {@code startOfLastWeek}.
     * Habits with no streak, or never completed, are left alone.
     *
     * @return how many habits were updated
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Habit h set h.currentStreak = 0
            where h.currentStreak > 0
              and h.lastCompletedDate is not null
              and ((h.frequency = :weekly and h.lastCompletedDate < :startOfLastWeek)
                or ((h.frequency is null or h.frequency <> :weekly) and h.lastCompletedDate < :yesterday))
            """)
    int resetLapsedStreaks(@Param("yesterday") LocalDate yesterday,
                           @Param("startOfLastWeek") LocalDate startOfLastWeek,
                           @Param("weekly") Habit.Frequency weekly);
}
