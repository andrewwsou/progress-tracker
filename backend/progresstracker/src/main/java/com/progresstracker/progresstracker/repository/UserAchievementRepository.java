package com.progresstracker.progresstracker.repository;

import com.progresstracker.progresstracker.model.Achievement;
import com.progresstracker.progresstracker.model.User;
import com.progresstracker.progresstracker.model.UserAchievement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


import java.time.LocalDateTime;
import java.util.List;

public interface UserAchievementRepository extends JpaRepository<UserAchievement, Long> {
    boolean existsByUserAndAchievement(User user, Achievement achievement);

    /**
     * Unlocks an achievement unless the user already has it. Two transactions racing to unlock
     * the same one both succeed and exactly one row exists afterwards, where a plain insert
     * would make the loser fail on the unique constraint and roll back its whole completion.
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


    @Query("""
    select ua
    from UserAchievement ua
    join fetch ua.achievement a
    where ua.user = :user
    order by ua.unlockedAt desc
""")
    List<UserAchievement> findByUserWithAchievementOrderByUnlockedAtDesc(@Param("user") User user);


}
