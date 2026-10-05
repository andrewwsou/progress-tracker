package com.progresstracker.progresstracker.repository;

import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.model.HabitEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface HabitEntryRepository extends JpaRepository<HabitEntry, Long> {

    long countByHabitAndCompletedDateBetween(Habit habit, LocalDate startInclusive, LocalDate endInclusive);

    /** Whether the habit has a completion on or after the date (later dates exist after a zone change). */
    boolean existsByHabitAndCompletedDateGreaterThanEqual(Habit habit, LocalDate date);

    /**
     * Each habit's completions from {@code weekStart} on, which is all that showing its progress
     * needs. One query for any number of habits; a habit with no such completion has no row.
     */
    @Query("""
            select he.habit.id as habitId,
                   sum(case when he.completedDate <= :weekEnd then 1 else 0 end) as thisWeek,
                   sum(case when he.completedDate = :today then 1 else 0 end) as onToday,
                   max(he.completedDate) as latest
            from HabitEntry he
            where he.habit.id in :habitIds and he.completedDate >= :weekStart
            group by he.habit.id
            """)
    List<PeriodProgress> findProgress(@Param("habitIds") Collection<Long> habitIds,
                                      @Param("today") LocalDate today,
                                      @Param("weekStart") LocalDate weekStart,
                                      @Param("weekEnd") LocalDate weekEnd);

    /** One habit's completions in the current week, as read by {@link #findProgress}. */
    interface PeriodProgress {
        Long getHabitId();

        /** Completions from Monday to Sunday of the week. */
        long getThisWeek();

        /** 1 if the habit was completed today, else 0. */
        long getOnToday();

        /** The latest completion; after the user moves west it can be later than today. */
        LocalDate getLatest();
    }

    /**
     * How many days in a row, ending at {@code date}, the habit was completed. Zero if it was not
     * completed on {@code date}. The worker computes streaks with the same statement, so both
     * modes reward the same history the same way.
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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from HabitEntry he where he.habit.id = :habitId")
    void deleteByHabitId(@Param("habitId") Long habitId);
}
