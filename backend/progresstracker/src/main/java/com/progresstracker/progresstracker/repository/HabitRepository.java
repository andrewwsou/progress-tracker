package com.progresstracker.progresstracker.repository;

import com.progresstracker.progresstracker.model.Habit;
import org.springframework.data.jpa.repository.JpaRepository;
import com.progresstracker.progresstracker.model.User;
import java.util.List;

public interface HabitRepository extends JpaRepository<Habit, Long> {
    List<Habit> findByUser(User user);
}
