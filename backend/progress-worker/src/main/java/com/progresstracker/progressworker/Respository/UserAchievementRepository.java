package com.progresstracker.progressworker.repository;

import com.progresstracker.progressworker.model.Achievement;
import com.progresstracker.progressworker.model.User;
import com.progresstracker.progressworker.model.UserAchievement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserAchievementRepository extends JpaRepository<UserAchievement, Long> {
    boolean existsByUserAndAchievement(User user, Achievement achievement);
    List<UserAchievement> findByUserOrderByUnlockedAtDesc(User user);
}
