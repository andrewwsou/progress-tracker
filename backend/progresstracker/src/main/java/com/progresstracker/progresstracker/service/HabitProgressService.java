package com.progresstracker.progresstracker.service;

import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.model.HabitEntry;
import com.progresstracker.progresstracker.model.User;
import com.progresstracker.progresstracker.outbox.CompletionEvent;
import com.progresstracker.progresstracker.outbox.OutboxEvent;
import com.progresstracker.progresstracker.outbox.OutboxEventRepository;
import com.progresstracker.progresstracker.repository.HabitEntryRepository;
import com.progresstracker.progresstracker.repository.HabitRepository;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

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
    private final JsonMapper jsonMapper;
    private final EntityManager entityManager;
    private final UserCalendar calendar;

    public HabitProgressService(
            HabitRepository habitRepository,
            HabitEntryRepository habitEntryRepository,
            AchievementService achievementService,
            OutboxEventRepository outboxEventRepository,
            JsonMapper jsonMapper,
            EntityManager entityManager,
            UserCalendar calendar
    ) {
        this.habitRepository = habitRepository;
        this.habitEntryRepository = habitEntryRepository;
        this.achievementService = achievementService;
        this.outboxEventRepository = outboxEventRepository;
        this.jsonMapper = jsonMapper;
        this.entityManager = entityManager;
        this.calendar = calendar;
    }

    @Transactional
    public Habit completeToday(Habit habit) {
        habit = lockAndReload(habit);
        LocalDate today = calendar.today(habit.getUser());

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

    /**
     * The habit passed in was read before this transaction started, and the nightly streak reset
     * may have changed it since. Lock its row and read it again, so the new streak is computed from
     * the row as it is now: a reset that already ran is seen, and one that runs now waits for this
     * completion and then finds the streak current. Reading it again also resets what the entity is
     * compared with when it is saved, so the new streak is written even when it equals the old copy's.
     */
    private Habit lockAndReload(Habit habit) {
        if (habitRepository.lockById(habit.getId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Habit not found"); // deleted meanwhile
        }
        if (entityManager.contains(habit)) {
            entityManager.refresh(habit);
            return habit;
        }
        return entityManager.find(Habit.class, habit.getId());
    }

    @Transactional
    public Habit recordCompletionOnly(Habit habit) {
        LocalDate today = calendar.today(habit.getUser());

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
            String payload = jsonMapper.writeValueAsString(event.toMessage());
            return new OutboxEvent(event.eventId(), CompletionEvent.TYPE, payload, now);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize completion event", e);
        }
    }

    /**
     * Whether the habit is already done for the period containing {@code today}: today (daily) or
     * this week (weekly). A completion dated after today counts too: after the user moves west,
     * their last completion can be dated "tomorrow" in the new zone, and completing again would
     * otherwise move the streak backwards.
     */
    public boolean alreadyCompletedForPeriod(Habit habit, LocalDate today) {
        LocalDate periodStart = habit.getFrequency() == Habit.Frequency.WEEKLY
                ? today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                : today;
        return habitEntryRepository.existsByHabitAndCompletedDateGreaterThanEqual(habit, periodStart);
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
