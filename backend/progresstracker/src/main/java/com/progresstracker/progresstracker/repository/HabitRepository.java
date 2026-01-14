package com.progresstracker.progresstracker.repository;

import com.progresstracker.progresstracker.model.Habit;
import org.springframework.data.jpa.repository.JpaRepository;
import com.progresstracker.progresstracker.model.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface HabitRepository extends JpaRepository<Habit, Long> {
    List<Habit> findByUser(User user);

    @Query("select coalesce(sum(h.xpTotal), 0) from Habit h where h.user = :user")
    long sumXpByUser(@Param("user") User user);
}
