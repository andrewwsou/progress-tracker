package com.progresstracker.progresstracker.automation;

import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.repository.HabitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Reconciles currentStreak for habits nobody has completed recently.
 *
 * Streaks are normally only recalculated on the next completion (see
 * HabitProgressService), so a habit a user stopped doing keeps showing its old
 * streak value indefinitely instead of reflecting the break. This is meant to
 * run on a daily schedule (nightly EventBridge -> Lambda -> this endpoint).
 */
@Service
public class StreakResetService {

    private static final Logger log = LoggerFactory.getLogger(StreakResetService.class);

    private final HabitRepository habitRepository;

    public StreakResetService(HabitRepository habitRepository) {
        this.habitRepository = habitRepository;
    }

    /**
     * One UPDATE for the whole table, instead of loading every habit and saving the lapsed
     * ones one at a time. The database decides which rows qualify at the moment it updates
     * them, so a habit completed while the job runs is judged on its new date, not a stale one.
     *
     * @return how many streaks were reset
     */
    @Transactional
    public int resetBrokenStreaks(LocalDate today) {
        // A daily streak is alive if the habit was completed today or yesterday.
        LocalDate yesterday = today.minusDays(1);
        // A weekly streak is alive if it was completed this week or last week (weeks start on Monday).
        LocalDate startOfLastWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);

        int resetCount = habitRepository.resetLapsedStreaks(yesterday, startOfLastWeek, Habit.Frequency.WEEKLY);
        log.info("STREAK_RESET count={} asOf={}", resetCount, today);
        return resetCount;
    }
}
