package com.progresstracker.progresstracker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.model.HabitEntry;
import com.progresstracker.progresstracker.model.User;
import com.progresstracker.progresstracker.outbox.CompletionEvent;
import com.progresstracker.progresstracker.outbox.OutboxEvent;
import com.progresstracker.progresstracker.outbox.OutboxEventRepository;
import com.progresstracker.progresstracker.repository.HabitEntryRepository;
import com.progresstracker.progresstracker.repository.HabitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.UUID;

@Service
public class HabitProgressService {

    private final HabitRepository habitRepository;
    private final HabitEntryRepository habitEntryRepository;
    private final AchievementService achievementService;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public HabitProgressService(
            HabitRepository habitRepository,
            HabitEntryRepository habitEntryRepository,
            AchievementService achievementService,
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper
    ) {
        this.habitRepository = habitRepository;
        this.habitEntryRepository = habitEntryRepository;
        this.achievementService = achievementService;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Habit completeToday(Habit habit) {
        LocalDate today = LocalDate.now();

        if (alreadyCompletedForPeriod(habit, today)) {
            return habit;
        }

        LocalDate last = habit.getLastCompletedDate();
        int nextStreak;

        if (last == null) {
            nextStreak = 1;
        } else {
            nextStreak = isConsecutivePeriod(habit, last, today) ? habit.getCurrentStreak() + 1 : 1;
        }

        habit.setCurrentStreak(nextStreak);
        habit.setLongestStreak(Math.max(habit.getLongestStreak(), nextStreak));
        habit.setLastCompletedDate(today);

        int xpEarned = 10 + Math.min(20, (nextStreak - 1) * 2);
        habit.setXpTotal(habit.getXpTotal() + xpEarned);

        habitEntryRepository.save(new HabitEntry(habit, today, xpEarned));

        Habit saved = habitRepository.save(habit);

        User user = habit.getUser();
        if (user != null) {
            achievementService.evaluateAndUnlock(user, saved);
        }

        return saved;
    }

    @Transactional
    public Habit recordCompletionOnly(Habit habit) {
        LocalDate today = LocalDate.now();

        if (alreadyCompletedForPeriod(habit, today)) {
            return habit;
        }

        habitEntryRepository.save(new HabitEntry(habit, today, 0));

        // Written in the same transaction as the row above: the completion and the event asking
        // the worker to reward it commit together or not at all. A request that loses the
        // unique-constraint race rolls back before reaching this line, so it leaves no event.
        outboxEventRepository.save(completionEvent(habit, today));
        return habit;
    }

    private OutboxEvent completionEvent(Habit habit, LocalDate date) {
        OffsetDateTime now = OffsetDateTime.now();
        CompletionEvent event = new CompletionEvent(
                UUID.randomUUID(), habit.getUser().getId(), habit.getId(), date, now);
        try {
            String payload = objectMapper.writeValueAsString(event.toMessage());
            return new OutboxEvent(event.eventId(), CompletionEvent.TYPE, payload, now);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize completion event", e);
        }
    }

    public boolean alreadyCompletedForPeriod(Habit habit, LocalDate today) {
        if (habit.getFrequency() == Habit.Frequency.WEEKLY) {
            LocalDate start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate end = start.plusDays(6);
            return habitEntryRepository.countByHabitAndCompletedDateBetween(habit, start, end) > 0;
        }
        return habitEntryRepository.findByHabitAndCompletedDate(habit, today).isPresent();
    }

    private boolean isConsecutivePeriod(Habit habit, LocalDate lastCompleted, LocalDate today) {
        if (habit.getFrequency() == Habit.Frequency.WEEKLY) {
            return isSameIsoWeek(lastCompleted, today.minusWeeks(1));
        }
        return today.equals(lastCompleted.plusDays(1));
    }

    private boolean isSameIsoWeek(LocalDate a, LocalDate b) {
        WeekFields wf = WeekFields.ISO;
        int wa = a.get(wf.weekOfWeekBasedYear());
        int ya = a.get(wf.weekBasedYear());
        int wb = b.get(wf.weekOfWeekBasedYear());
        int yb = b.get(wf.weekBasedYear());
        return wa == wb && ya == yb;
    }
}
