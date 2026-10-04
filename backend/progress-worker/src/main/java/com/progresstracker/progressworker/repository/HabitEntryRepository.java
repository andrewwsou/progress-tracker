package com.progresstracker.progressworker.repository;

import com.progresstracker.progressworker.model.Habit;
import com.progresstracker.progressworker.model.HabitEntry;
import com.progresstracker.progressworker.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface HabitEntryRepository extends JpaRepository<HabitEntry, Long> {

    Optional<HabitEntry> findByHabitAndCompletedDate(Habit habit, LocalDate completedDate);

    /** How many completions the user has, across all their habits. */
    long countByHabitUser(User user);

    /**
     * How many days in a row, ending at {@code date}, the habit was completed. Zero if it was not
     * completed on {@code date}.
     *
     * One statement, however long the streak is. Number the completed days from {@code date}
     * backwards: 1, 2, 3 and so on. In an unbroken run each earlier day is one day older and one
     * position later, so day + position stays at date + 1. The first missed day shifts that sum
     * for everything older, so counting the rows that still add up to date + 1 gives the run length.
     */
    @Query(value = """
            select count(*) from (
                select completed_date + cast(row_number() over (order by completed_date desc) as integer) as run_anchor
                from habit_entries
                where habit_id = :habitId and completed_date <= :date
            ) numbered_days
            where run_anchor = cast(:date as date) + 1
            """, nativeQuery = true)
    long dailyStreakEndingAt(@Param("habitId") Long habitId, @Param("date") LocalDate date);

    /**
     * How many weeks in a row, ending with the week that starts on {@code weekStart} (a Monday),
     * have at least one completion. Same idea as the daily version, with weeks in place of days.
     */
    @Query(value = """
            select count(*) from (
                select week_start + 7 * cast(row_number() over (order by week_start desc) as integer) as run_anchor
                from (
                    select distinct cast(date_trunc('week', cast(completed_date as timestamp)) as date) as week_start
                    from habit_entries
                    where habit_id = :habitId and completed_date <= :weekEnd
                ) completed_weeks
            ) numbered_weeks
            where run_anchor = cast(:weekStart as date) + 7
            """, nativeQuery = true)
    long weeklyStreakEndingAt(@Param("habitId") Long habitId,
                              @Param("weekStart") LocalDate weekStart,
                              @Param("weekEnd") LocalDate weekEnd);
}
