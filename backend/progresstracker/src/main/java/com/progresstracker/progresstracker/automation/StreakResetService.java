package com.progresstracker.progresstracker.automation;

import com.progresstracker.progresstracker.repository.HabitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;


/**
 * Reconciles currentStreak for habits nobody has completed recently.
 *
 * Streaks are normally only recalculated on the next completion (see
 * HabitProgressService), so a habit a user stopped doing keeps showing its old
 * streak value indefinitely instead of reflecting the break. This is meant to
 * run on an hourly schedule (EventBridge -> Lambda -> this endpoint).
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
     * Each habit is judged on its owner's calendar, so the job runs hourly: within an hour of
     * midnight in any time zone, that zone's lapsed streaks are reset.
     *
     * @return how many streaks were reset
     */
    @Transactional
    public int resetBrokenStreaks(Instant now) {
        int resetCount = habitRepository.resetLapsedStreaks(now.atOffset(ZoneOffset.UTC));
        log.info("STREAK_RESET count={} at={}", resetCount, now);
        return resetCount;
    }
}
