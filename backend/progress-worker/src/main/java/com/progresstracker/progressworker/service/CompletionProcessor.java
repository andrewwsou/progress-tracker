package com.progresstracker.progressworker.service;

import com.progresstracker.progressworker.model.Habit;
import com.progresstracker.progressworker.model.HabitEntry;
import com.progresstracker.progressworker.model.User;
import com.progresstracker.progressworker.repository.HabitEntryRepository;
import com.progresstracker.progressworker.repository.HabitRepository;
import com.progresstracker.progressworker.repository.ProcessedEventRepository;
import com.progresstracker.progressworker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;

/**
 * Applies the reward for one completion event: XP, streaks, achievements, and the email.
 *
 * The queue delivers each event at least once, in no particular order, and several workers may
 * run at the same time. This gives the same result however often and in whatever order events arrive:
 * <ol>
 *   <li>the event id is recorded in {@code processed_events} in the same transaction as the
 *       reward, so a repeat of the same event is recognised straight away and ignored;</li>
 *   <li>a row lock on the user makes reward transactions for one user run one at a time;</li>
 *   <li>under that lock, a completion that already has XP is never rewarded again. This is what
 *       makes one completion earn one reward, even if it arrives as two different events;</li>
 *   <li>the current streak only moves forward, so an old event arriving late cannot rewind it;</li>
 *   <li>any failure rolls everything back, including the event id, and the queue redelivers.</li>
 * </ol>
 */
@Service
public class CompletionProcessor {

    private static final Logger log = LoggerFactory.getLogger(CompletionProcessor.class);

    private final HabitRepository habitRepository;
    private final HabitEntryRepository habitEntryRepository;
    private final UserRepository userRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final AchievementService achievementService;
    private final EmailService emailService;

    public CompletionProcessor(
            HabitRepository habitRepository,
            HabitEntryRepository habitEntryRepository,
            UserRepository userRepository,
            ProcessedEventRepository processedEventRepository,
            AchievementService achievementService,
            EmailService emailService
    ) {
        this.habitRepository = habitRepository;
        this.habitEntryRepository = habitEntryRepository;
        this.userRepository = userRepository;
        this.processedEventRepository = processedEventRepository;
        this.achievementService = achievementService;
        this.emailService = emailService;
    }

    /**
     * @return true if the reward was applied; false if there was nothing to do (the event or the
     *         completion was already handled, or the habit no longer exists)
     */
    // The deadline bounds how long this can wait on another transaction's lock. Past it, the
    // attempt is rolled back and the queue redelivers, instead of the poller hanging.
    @Transactional(timeoutString = "${worker.processTimeoutSeconds:5}")
    public boolean process(UUID eventId, Long userId, Long habitId, LocalDate date, OffsetDateTime occurredAt) {
        // 1. Claim the event. A duplicate delivery stops here.
        if (processedEventRepository.insertIfAbsent(eventId, occurredAt) == 0) {
            return false;
        }

        // 2. One reward transaction per user at a time. Everything read below is read after
        //    the lock is held, so it reflects whatever the previous transaction committed.
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("User not found"));

        Habit habit = habitRepository.findById(habitId).orElse(null);
        if (habit == null) {
            // Deleted between the completion and now. That is permanent, so retrying cannot help:
            // record the event as handled and let the message go.
            log.warn("No reward for event {}: habit {} no longer exists", eventId, habitId);
            return false;
        }
        if (habit.getUser() == null || !user.getId().equals(habit.getUser().getId())) {
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

        // Required, not just a safety net. Read while the user lock is held, this is what makes
        // one completion earn one reward. The event-id check above only recognises a repeat of
        // the same event; the same completion can also arrive under a different id (a message
        // sent before events carried ids, or a manual re-send).
        if (entry.getXpEarned() > 0) {
            return false;
        }

        int streak = computeStreakEndingAt(habit, date);
        int xpEarned = 10 + Math.min(20, (streak - 1) * 2);

        entry.setXpEarned(xpEarned);
        habitEntryRepository.save(entry);

        // Events can arrive out of order (retries, redrives from the dead-letter queue).
        // Only the most recent day decides the current streak; an older day never overwrites it.
        LocalDate lastCompleted = habit.getLastCompletedDate();
        if (lastCompleted == null || !date.isBefore(lastCompleted)) {
            habit.setCurrentStreak(streak);
            habit.setLastCompletedDate(date);
        }
        habit.setLongestStreak(Math.max(habit.getLongestStreak(), streak));
        habit.setXpTotal(habit.getXpTotal() + xpEarned);

        Habit saved = habitRepository.save(habit);

        achievementService.evaluateAndUnlock(user, saved);
        emailService.queueCompletionEmail(user, saved.getName());
        return true;
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
