package com.progresstracker.progressworker.repository;

import com.progresstracker.progressworker.model.UserAchievement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Set;

public interface UserAchievementRepository extends JpaRepository<UserAchievement, Long> {

    /** The codes of everything the user has unlocked, in one query. */
    @Query("select ua.achievement.code from UserAchievement ua where ua.user.id = :userId")
    Set<String> findUnlockedCodes(@Param("userId") Long userId);

    /**
     * Unlocks an achievement unless the user already has it, without failing on the unique
     * constraint if something else unlocked it first.
     *
     * @return 1 if this call unlocked it, 0 if it was already unlocked
     */
    @Modifying
    @Query(value = """
            insert into user_achievement (user_id, achievement_id, unlocked_at)
            values (:userId, :achievementId, :unlockedAt)
            on conflict (user_id, achievement_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId,
                       @Param("achievementId") Long achievementId,
                       @Param("unlockedAt") LocalDateTime unlockedAt);
}
