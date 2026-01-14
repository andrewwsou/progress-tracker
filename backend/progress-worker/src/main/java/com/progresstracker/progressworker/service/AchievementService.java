package com.progresstracker.progressworker.service;

import com.progresstracker.progressworker.model.Achievement;
import com.progresstracker.progressworker.model.Habit;
import com.progresstracker.progressworker.model.User;
import com.progresstracker.progressworker.model.UserAchievement;
import com.progresstracker.progressworker.repository.AchievementRepository;
import com.progresstracker.progressworker.repository.HabitEntryRepository;
import com.progresstracker.progressworker.repository.HabitRepository;
import com.progresstracker.progressworker.repository.UserAchievementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
    public List<UserAchievement> evaluateAndUnlock(User user, Habit justUpdatedHabit) {
        ensureDefaultAchievements();

        List<UserAchievement> newlyUnlocked = new ArrayList<>();

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
                newlyUnlocked.add(unlock(user, first));
            }
        }

        Achievement streak7 = achievementRepository.findByCode(STREAK_7).orElseThrow();
        if (!userAchievementRepository.existsByUserAndAchievement(user, streak7)) {
            if (justUpdatedHabit.getCurrentStreak() >= streak7.getThreshold()) {
                newlyUnlocked.add(unlock(user, streak7));
            }
        }

        Achievement xp100 = achievementRepository.findByCode(XP_100).orElseThrow();
        if (!userAchievementRepository.existsByUserAndAchievement(user, xp100)) {
            long totalXp = habitRepository.sumXpByUser(user);
            if (totalXp >= xp100.getThreshold()) {
                newlyUnlocked.add(unlock(user, xp100));
            }
        }

        return newlyUnlocked;
    }

    private UserAchievement unlock(User user, Achievement achievement) {
        UserAchievement ua = new UserAchievement(user, achievement, LocalDateTime.now());
        return userAchievementRepository.save(ua);
    }
}
