package com.progresstracker.progresstracker.repository;

import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.model.HabitEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

public interface HabitEntryRepository extends JpaRepository<HabitEntry, Long> {

    Optional<HabitEntry> findByHabitAndCompletedDate(Habit habit, LocalDate completedDate);

    long countByHabitAndCompletedDateBetween(Habit habit, LocalDate startInclusive, LocalDate endInclusive);

    @Query("select coalesce(sum(he.xpEarned), 0) from HabitEntry he " +
            "where he.habit = :habit and he.completedDate between :startInclusive and :endInclusive")
    long sumXpByHabitAndCompletedDateBetween(@Param("habit") Habit habit,
                                              @Param("startInclusive") LocalDate startInclusive,
                                              @Param("endInclusive") LocalDate endInclusive);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from HabitEntry he where he.habit.id = :habitId")
    void deleteByHabitId(@Param("habitId") Long habitId);
}
