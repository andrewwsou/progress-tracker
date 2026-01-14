package com.progresstracker.progresstracker.repository;

import com.progresstracker.progresstracker.model.Achievement;
import com.progresstracker.progresstracker.model.User;
import com.progresstracker.progresstracker.model.UserAchievement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserAchievementRepository extends JpaRepository<UserAchievement, Long> {
    List<UserAchievement> findByUserOrderByUnlockedAtDesc(User user);
    Optional<UserAchievement> findByUserAndAchievement(User user, Achievement achievement);
    boolean existsByUserAndAchievement(User user, Achievement achievement);
}
