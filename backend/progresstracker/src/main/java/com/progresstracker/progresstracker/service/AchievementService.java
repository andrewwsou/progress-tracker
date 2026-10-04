package com.progresstracker.progresstracker.service;

import com.progresstracker.progresstracker.model.Achievement;
import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.model.User;
import com.progresstracker.progresstracker.model.UserAchievement;
import com.progresstracker.progresstracker.repository.AchievementRepository;
import com.progresstracker.progresstracker.repository.HabitEntryRepository;
import com.progresstracker.progresstracker.repository.HabitRepository;
import com.progresstracker.progresstracker.repository.UserAchievementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.progresstracker.progresstracker.dto.UserAchievementDto;


import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
public class AchievementService {

    public static final String FIRST_COMPLETION = "FIRST_COMPLETION";
    public static final String STREAK_7 = "STREAK_7";
    public static final String XP_100 = "XP_100";

    private final AchievementRepository achievementRepository;
    private final UserAchievementRepository userAchievementRepository;
    private final HabitRepository habitRepository;
    private final HabitEntryRepository habitEntryRepository;

    public AchievementService(
            AchievementRepository achievementRepository,
            UserAchievementRepository userAchievementRepository,
            HabitRepository habitRepository,
            HabitEntryRepository habitEntryRepository
    ) {
        this.achievementRepository = achievementRepository;
        this.userAchievementRepository = userAchievementRepository;
        this.habitRepository = habitRepository;
        this.habitEntryRepository = habitEntryRepository;
    }

    @Transactional
    public void ensureDefaultAchievements() {
        upsert(FIRST_COMPLETION, "First Step", "Complete a habit for the first time.", 1, "COMPLETION");
        upsert(STREAK_7, "On a Roll", "Reach a 7-day streak on any habit.", 7, "STREAK");
        upsert(XP_100, "Level Up", "Earn 100 total XP across all habits.", 100, "XP");
    }

    private void upsert(String code, String name, String description, int threshold, String type) {
        Achievement a = achievementRepository.findByCode(code).orElse(null);
        if (a == null) {
            achievementRepository.save(new Achievement(code, name, description, threshold, type));
            return;
        }

        boolean changed = false;

        if (!name.equals(a.getName())) {
            a.setName(name);
            changed = true;
        }
        if (!description.equals(a.getDescription())) {
            a.setDescription(description);
            changed = true;
        }
        if (a.getThreshold() == null || a.getThreshold() != threshold) {
            a.setThreshold(threshold);
            changed = true;
        }
        if (a.getType() == null || !type.equals(a.getType())) {
            a.setType(type);
            changed = true;
        }

        if (changed) {
            achievementRepository.save(a);
        }
    }

    @Transactional
    public List<Achievement> evaluateAndUnlock(User user, Habit justUpdatedHabit) {
        ensureDefaultAchievements();

        List<Achievement> newlyUnlocked = new ArrayList<>();

        Achievement first = achievementRepository.findByCode(FIRST_COMPLETION).orElseThrow();
        if (!userAchievementRepository.existsByUserAndAchievement(user, first)) {
            long totalCompletions = 0;
            for (Habit h : habitRepository.findByUser(user)) {
                LocalDate today = LocalDate.now();
                totalCompletions += habitEntryRepository.countByHabitAndCompletedDateBetween(
                        h, LocalDate.of(1970, 1, 1), today
                );
                if (totalCompletions > 0) break;
            }
            if (totalCompletions >= first.getThreshold()) {
                if (unlock(user, first)) newlyUnlocked.add(first);
            }
        }

        Achievement streak7 = achievementRepository.findByCode(STREAK_7).orElseThrow();
        if (!userAchievementRepository.existsByUserAndAchievement(user, streak7)) {
            // The longest streak, not the current one: it only ever grows, so the result does not
            // depend on the order completions were processed in.
            if (justUpdatedHabit.getLongestStreak() >= streak7.getThreshold()) {
                if (unlock(user, streak7)) newlyUnlocked.add(streak7);
            }
        }

        Achievement xp100 = achievementRepository.findByCode(XP_100).orElseThrow();
        if (!userAchievementRepository.existsByUserAndAchievement(user, xp100)) {
            long totalXp = habitRepository.sumXpByUser(user);
            if (totalXp >= xp100.getThreshold()) {
                if (unlock(user, xp100)) newlyUnlocked.add(xp100);
            }
        }

        return newlyUnlocked;
    }

    /** Safe to race: unlocking something already unlocked is a no-op, never a constraint failure. */
    private boolean unlock(User user, Achievement achievement) {
        return userAchievementRepository.insertIfAbsent(user.getId(), achievement.getId(), LocalDateTime.now()) == 1;
    }

    public List<UserAchievementDto> getUnlockedFor(User user) {
        return userAchievementRepository.findByUserWithAchievementOrderByUnlockedAtDesc(user)
                .stream()
                .map(ua -> new UserAchievementDto(
                        ua.getId(),                         // userAchievementId
                        ua.getAchievement().getId(),         // achievementId
                        ua.getAchievement().getCode(),       // code
                        ua.getAchievement().getName(),       // name
                        ua.getAchievement().getDescription(),// description
                        ua.getAchievement().getThreshold(),  // threshold
                        ua.getAchievement().getType(),       // type
                        ua.getUnlockedAt().atZone(ZoneId.systemDefault()).toOffsetDateTime() // unlockedAt
                ))
                .toList();
    }

}
