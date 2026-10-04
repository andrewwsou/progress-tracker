package com.progresstracker.progresstracker.automation;

import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.repository.HabitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.WeekFields;

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

    @Transactional
    public int resetBrokenStreaks(LocalDate today) {
        int resetCount = 0;

        for (Habit habit : habitRepository.findAll()) {
            if (habit.getCurrentStreak() <= 0) {
                continue;
            }
            if (isStreakBroken(habit, today)) {
                habit.setCurrentStreak(0);
                habitRepository.save(habit);
                resetCount++;
                log.info("STREAK_RESET habitId={} lastCompletedDate={}", habit.getId(), habit.getLastCompletedDate());
            }
        }

        return resetCount;
    }

    private boolean isStreakBroken(Habit habit, LocalDate today) {
        LocalDate last = habit.getLastCompletedDate();
        if (last == null) {
            return false;
        }

        if (habit.getFrequency() == Habit.Frequency.WEEKLY) {
            return !isCurrentOrPreviousIsoWeek(last, today);
        }

        return last.isBefore(today.minusDays(1));
    }

    private boolean isCurrentOrPreviousIsoWeek(LocalDate last, LocalDate today) {
        WeekFields wf = WeekFields.ISO;
        boolean sameAsCurrent = isSameIsoWeek(wf, last, today);
        boolean sameAsPrevious = isSameIsoWeek(wf, last, today.minusWeeks(1));
        return sameAsCurrent || sameAsPrevious;
    }

    private boolean isSameIsoWeek(WeekFields wf, LocalDate a, LocalDate b) {
        return a.get(wf.weekOfWeekBasedYear()) == b.get(wf.weekOfWeekBasedYear())
                && a.get(wf.weekBasedYear()) == b.get(wf.weekBasedYear());
    }
}
