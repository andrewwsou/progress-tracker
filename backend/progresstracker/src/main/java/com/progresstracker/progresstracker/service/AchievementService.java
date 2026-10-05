package com.progresstracker.progresstracker.service;

import com.progresstracker.progresstracker.model.Achievement;
import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.model.User;
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

/** Unlocks achievements. Their definitions are rows seeded by a migration (V4__seed_achievements.sql). */
@Service
public class AchievementService {

    public static final String FIRST_COMPLETION = "FIRST_COMPLETION";
    public static final String STREAK_7 = "STREAK_7";
    public static final String XP_100 = "XP_100";

    private final AchievementRepository achievementRepository;
    private final UserAchievementRepository userAchievementRepository;
    private final HabitRepository habitRepository;
    private final HabitEntryRepository habitEntryRepository;
    private final UserCalendar calendar;

    public AchievementService(
            AchievementRepository achievementRepository,
            UserAchievementRepository userAchievementRepository,
            HabitRepository habitRepository,
            HabitEntryRepository habitEntryRepository,
            UserCalendar calendar
    ) {
        this.achievementRepository = achievementRepository;
        this.userAchievementRepository = userAchievementRepository;
        this.habitRepository = habitRepository;
        this.habitEntryRepository = habitEntryRepository;
        this.calendar = calendar;
    }

    @Transactional
    public List<Achievement> evaluateAndUnlock(User user, Habit justUpdatedHabit) {
        List<Achievement> newlyUnlocked = new ArrayList<>();

        Achievement first = achievementRepository.findByCode(FIRST_COMPLETION).orElseThrow();
        if (!userAchievementRepository.existsByUserAndAchievement(user, first)) {
            long totalCompletions = 0;
            for (Habit h : habitRepository.findByUser(user)) {
                LocalDate today = calendar.today(user); // the user's day: entries are dated in it
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
