package com.progresstracker.progresstracker.automation;

import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.model.User;
import com.progresstracker.progresstracker.repository.HabitEntryRepository;
import com.progresstracker.progresstracker.repository.HabitRepository;
import com.progresstracker.progresstracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a per-user activity summary for a given week. Meant to run on a
 * weekly schedule (EventBridge -> Lambda -> this endpoint) and queue a
 * summary email per user (logged here in place of an SES integration).
 */
@Service
public class WeeklySummaryService {

    private static final Logger log = LoggerFactory.getLogger(WeeklySummaryService.class);

    private final UserRepository userRepository;
    private final HabitRepository habitRepository;
    private final HabitEntryRepository habitEntryRepository;

    public WeeklySummaryService(UserRepository userRepository,
                                 HabitRepository habitRepository,
                                 HabitEntryRepository habitEntryRepository) {
        this.userRepository = userRepository;
        this.habitRepository = habitRepository;
        this.habitEntryRepository = habitEntryRepository;
    }

    @Transactional(readOnly = true)
    public List<WeeklySummary> generateSummaries(LocalDate weekStart) {
        LocalDate weekEnd = weekStart.plusDays(6);
        List<WeeklySummary> summaries = new ArrayList<>();

        for (User user : userRepository.findAll()) {
            List<Habit> habits = habitRepository.findByUser(user);

            long completions = 0;
            long xpEarned = 0;
            for (Habit habit : habits) {
                completions += habitEntryRepository.countByHabitAndCompletedDateBetween(habit, weekStart, weekEnd);
                xpEarned += habitEntryRepository.sumXpByHabitAndCompletedDateBetween(habit, weekStart, weekEnd);
            }

            WeeklySummary summary = new WeeklySummary(
                    user.getId(), user.getEmail(), weekStart, weekEnd, habits.size(), completions, xpEarned);
            summaries.add(summary);

            log.info("WEEKLY_SUMMARY_QUEUED userId={} email={} habits={} completions={} xpEarned={} week={}..{}",
                    summary.userId(), summary.email(), summary.habitCount(), summary.completions(),
                    summary.xpEarned(), weekStart, weekEnd);
        }

        return summaries;
    }
}
