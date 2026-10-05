package com.progresstracker.progressworker.repository;

import com.progresstracker.progressworker.model.Habit;
import com.progresstracker.progressworker.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

public interface HabitRepository extends JpaRepository<Habit, Long> {

    @Query("select coalesce(sum(h.xpTotal), 0) from Habit h where h.user = :user")
    long sumXpByUser(@Param("user") User user);

    /**
     * Applies one reward to the habit in a single statement, so every column is computed from the
     * row as it is now, not from a copy read earlier in the transaction. That matters because the
     * API's hourly reset can zero the streak in between: writing back an earlier copy could then
     * leave the reset's zero in place of the new streak.
     *
     * The current streak and last completion only move forward: a completion older than the
     * latest one (a retry or a redrive arriving late) adds XP but never rewinds the streak.
     * Pending changes are flushed first (the entry's XP), and the persistence context is cleared
     * afterwards so the next read sees the updated row.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update habit set
                current_streak      = case when last_completed_date is null or last_completed_date <= cast(:date as date)
                                           then :streak else current_streak end,
                last_completed_date = case when last_completed_date is null or last_completed_date <= cast(:date as date)
                                           then cast(:date as date) else last_completed_date end,
                longest_streak      = greatest(longest_streak, :streak),
                xp_total            = xp_total + :xp
            where id = :habitId
            """, nativeQuery = true)
    int applyReward(@Param("habitId") Long habitId,
                    @Param("date") LocalDate date,
                    @Param("streak") int streak,
                    @Param("xp") int xp);
}
