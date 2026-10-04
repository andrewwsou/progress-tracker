package com.progresstracker.progressworker.repository;

import com.progresstracker.progressworker.model.Habit;
import com.progresstracker.progressworker.model.HabitEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface HabitEntryRepository extends JpaRepository<HabitEntry, Long> {

    Optional<HabitEntry> findByHabitAndCompletedDate(Habit habit, LocalDate completedDate);

    boolean existsByHabitAndCompletedDate(Habit habit, LocalDate completedDate);

    long countByHabitAndCompletedDateBetween(Habit habit, LocalDate startInclusive, LocalDate endInclusive);
}
