package com.progresstracker.progresstracker.repository;

import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.model.HabitEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface HabitEntryRepository extends JpaRepository<HabitEntry, Long> {
    Optional<HabitEntry> findByHabitAndCompletedDate(Habit habit, LocalDate completedDate);
}
