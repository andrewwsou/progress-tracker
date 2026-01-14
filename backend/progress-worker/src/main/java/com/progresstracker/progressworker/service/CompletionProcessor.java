package com.progresstracker.progressworker.service;

import com.progresstracker.progressworker.model.Habit;
import com.progresstracker.progressworker.model.HabitEntry;
import com.progresstracker.progressworker.model.User;
import com.progresstracker.progressworker.repository.HabitEntryRepository;
import com.progresstracker.progressworker.repository.HabitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

@Service
public class CompletionProcessor {

    private final HabitRepository habitRepository;
    private final HabitEntryRepository habitEntryRepository;
    private final AchievementService achievementService;
    private final EmailService emailService;

    public CompletionProcessor(
            HabitRepository habitRepository,
            HabitEntryRepository habitEntryRepository,
            AchievementService achievementService,
            EmailService emailService
    ) {
        this.habitRepository = habitRepository;
        this.habitEntryRepository = habitEntryRepository;
        this.achievementService = achievementService;
        this.emailService = emailService;
    }

    @Transactional
    public void process(Long userId, Long habitId, LocalDate date) {
        Habit habit = habitRepository.findById(habitId).orElseThrow();

        User user = habit.getUser();
        if (user == null || user.getId() == null || !user.getId().equals(userId)) {
            throw new IllegalStateException("Habit does not belong to user");
        }

        HabitEntry entry = habitEntryRepository.findByHabitAndCompletedDate(habit, date)
                .orElseGet(() -> {
                    HabitEntry he = new HabitEntry();
                    he.setHabit(habit);
                    he.setCompletedDate(date);
                    he.setXpEarned(0);
                    return habitEntryRepository.save(he);
                });

        if (entry.getXpEarned() > 0) {
            return;
        }

        int streak = computeStreakEndingAt(habit, date);
        int xpEarned = 10 + Math.min(20, (streak - 1) * 2);

        entry.setXpEarned(xpEarned);
        habitEntryRepository.save(entry);

        habit.setCurrentStreak(streak);
        habit.setLongestStreak(Math.max(habit.getLongestStreak(), streak));
        habit.setLastCompletedDate(maxDate(habit.getLastCompletedDate(), date));
        habit.setXpTotal(habit.getXpTotal() + xpEarned);

        Habit saved = habitRepository.save(habit);

        achievementService.evaluateAndUnlock(user, saved);
        emailService.queueCompletionEmail(user, saved.getName());
    }

    private LocalDate maxDate(LocalDate a, LocalDate b) {
        if (a == null) return b;
        return a.isAfter(b) ? a : b;
    }

    private int computeStreakEndingAt(Habit habit, LocalDate date) {
        if (habit.getFrequency() == Habit.Frequency.WEEKLY) {
            return computeWeeklyStreakEndingAt(habit, date);
        }
        return computeDailyStreakEndingAt(habit, date);
    }

    private int computeDailyStreakEndingAt(Habit habit, LocalDate date) {
        int streak = 0;
        LocalDate d = date;
        while (habitEntryRepository.existsByHabitAndCompletedDate(habit, d)) {
            streak++;
            d = d.minusDays(1);
        }
        return Math.max(streak, 1);
    }

    private int computeWeeklyStreakEndingAt(Habit habit, LocalDate date) {
        int streak = 0;
        LocalDate weekCursor = date;

        while (true) {
            LocalDate start = weekCursor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate end = start.plusDays(6);

            long count = habitEntryRepository.countByHabitAndCompletedDateBetween(habit, start, end);
            if (count <= 0) break;

            streak++;
            weekCursor = start.minusDays(1);
        }

        return Math.max(streak, 1);
    }
}
