package com.progresstracker.progressworker.repository;

import com.progresstracker.progressworker.model.Habit;
import com.progresstracker.progressworker.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


public interface HabitRepository extends JpaRepository<Habit, Long> {

    @Query("select coalesce(sum(h.xpTotal), 0) from Habit h where h.user = :user")
    long sumXpByUser(@Param("user") User user);
}
