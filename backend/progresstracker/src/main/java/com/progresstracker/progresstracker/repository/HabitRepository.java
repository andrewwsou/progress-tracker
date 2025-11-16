package com.progresstracker.progresstracker.repository;

import com.progresstracker.progresstracker.model.Habit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HabitRepository extends JpaRepository<Habit, Long> {
}
