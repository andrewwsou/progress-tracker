package com.progresstracker.progresstracker.service;

import java.time.Clock;
import java.time.ZoneOffset;
import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.model.HabitEntry;
import com.progresstracker.progresstracker.model.User;
import com.progresstracker.progresstracker.outbox.OutboxEvent;
import com.progresstracker.progresstracker.outbox.OutboxEventRepository;
import com.progresstracker.progresstracker.repository.HabitEntryRepository;
import com.progresstracker.progresstracker.repository.HabitRepository;
import com.progresstracker.progresstracker.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HabitProgressServiceTest {

    @Mock
    private HabitRepository habitRepository;

    @Mock
    private HabitEntryRepository habitEntryRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AchievementService achievementService;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private EntityManager entityManager;

    /** Every test runs on this day, a Thursday, unless it says otherwise (the users are in UTC, the default). */
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 16);

    /** The Monday of TODAY's week. */
    private static final LocalDate THIS_MONDAY = LocalDate.of(2026, 7, 13);

    private HabitProgressService service;

    @BeforeEach
    void setUp() {
        service = serviceOn(TODAY);
        lenient().when(habitRepository.save(any(Habit.class))).thenAnswer(inv -> inv.getArgument(0));
        // The habit exists and is managed: completeToday locks its row and refreshes it in place.
        lenient().when(habitRepository.lockById(any())).thenAnswer(inv -> Optional.of(inv.getArgument(0, Long.class)));
        lenient().when(entityManager.contains(any())).thenReturn(true);
    }

    private HabitProgressService serviceOn(LocalDate day) {
        return new HabitProgressService(
                habitRepository, habitEntryRepository, userRepository, achievementService, outboxEventRepository,
                new JsonMapper(), entityManager,
                new UserCalendar(Clock.fixed(day.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)));
    }

    private Habit weeklyHabit(int currentStreak, int longestStreak, LocalDate lastCompleted) {
        Habit habit = dailyHabit(currentStreak, longestStreak, lastCompleted);
        habit.setFrequency(Habit.Frequency.WEEKLY);
        return habit;
    }

    private Habit dailyHabit(int currentStreak, int longestStreak, LocalDate lastCompleted) {
        User user = new User("u@example.com", "hash");
        user.setId(7L);
        Habit habit = new Habit(user, "Read", "desc", Habit.Frequency.DAILY);
        habit.setId(42L);
        habit.setCurrentStreak(currentStreak);
        habit.setLongestStreak(longestStreak);
        habit.setLastCompletedDate(lastCompleted);
        return habit;
    }

    @Test
    void completeToday_firstEverCompletion_startsStreakAtOne() {
        Habit habit = dailyHabit(0, 0, null);
        when(habitEntryRepository.existsByHabitAndCompletedDateGreaterThanEqual(eq(habit), any())).thenReturn(false);

        Habit result = service.completeToday(habit);

        assertThat(result.getCurrentStreak()).isEqualTo(1);
        assertThat(result.getLongestStreak()).isEqualTo(1);
        assertThat(result.getXpTotal()).isEqualTo(10);
        verify(achievementService).evaluateAndUnlock(eq(habit.getUser()), eq(habit));
    }

    @Test
    void completeToday_continuesTheRunOfDaysEndingYesterday() {
        Habit habit = dailyHabit(4, 4, TODAY.minusDays(1));
        when(habitEntryRepository.existsByHabitAndCompletedDateGreaterThanEqual(habit, TODAY)).thenReturn(false);
        when(habitEntryRepository.dailyStreakEndingAt(42L, TODAY.minusDays(1))).thenReturn(4L);

        Habit result = service.completeToday(habit);

        assertThat(result.getCurrentStreak()).isEqualTo(5);
        assertThat(result.getLongestStreak()).isEqualTo(5);
        assertThat(result.getXpTotal()).isEqualTo(18); // 10 + min(20, (5-1)*2)
    }

    @Test
    void completeToday_afterGapInDays_resetsStreakToOne() {
        LocalDate threeDaysAgo = TODAY.minusDays(3);
        Habit habit = dailyHabit(9, 9, threeDaysAgo);
        when(habitEntryRepository.existsByHabitAndCompletedDateGreaterThanEqual(habit, TODAY)).thenReturn(false);
        when(habitEntryRepository.dailyStreakEndingAt(42L, TODAY.minusDays(1))).thenReturn(0L); // nothing yesterday

        Habit result = service.completeToday(habit);

        assertThat(result.getCurrentStreak()).isEqualTo(1);
        assertThat(result.getLongestStreak()).isEqualTo(9); // longest streak is never lowered
    }

    @Test
    void completeToday_aWeeklyHabitContinuesTheRunOfWeeksEndingLastWeek() {
        Habit habit = weeklyHabit(3, 3, LocalDate.of(2026, 7, 8));
        when(habitEntryRepository.existsByHabitAndCompletedDateGreaterThanEqual(habit, THIS_MONDAY)).thenReturn(false);
        when(habitEntryRepository.weeklyStreakEndingAt(42L, LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 12)))
                .thenReturn(3L);

        Habit result = service.completeToday(habit);

        assertThat(result.getCurrentStreak()).isEqualTo(4);
        assertThat(result.getXpTotal()).isEqualTo(16); // 10 + (4-1)*2
    }

    @Test
    void completeToday_weeksRunOnAcrossTheIsoYearBoundary() {
        // Sunday 10 January 2027 is in 2027-W01, which starts on Monday 4 January. The week before
        // it is 2026-W53: Monday 28 December to Sunday 3 January, across the change of year.
        HabitProgressService onSunday = serviceOn(LocalDate.of(2027, 1, 10));
        Habit habit = weeklyHabit(3, 3, LocalDate.of(2026, 12, 28));
        when(habitEntryRepository.existsByHabitAndCompletedDateGreaterThanEqual(habit, LocalDate.of(2027, 1, 4)))
                .thenReturn(false);
        when(habitEntryRepository.weeklyStreakEndingAt(42L, LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3)))
                .thenReturn(3L);

        Habit result = onSunday.completeToday(habit);

        assertThat(result.getCurrentStreak()).isEqualTo(4);
        assertThat(result.getLastCompletedDate()).isEqualTo(LocalDate.of(2027, 1, 10));
    }

    @Test
    void completeToday_countsTheStreakFromTheHistoryNotTheStoredCounter() {
        // The stored streak is 6 days, but the habit was switched to weekly since. The worker counts
        // weeks from the completions, and so must the sync path: last week, then this one.
        Habit habit = weeklyHabit(6, 6, LocalDate.of(2026, 7, 12));
        when(habitEntryRepository.existsByHabitAndCompletedDateGreaterThanEqual(habit, THIS_MONDAY)).thenReturn(false);
        when(habitEntryRepository.weeklyStreakEndingAt(42L, LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 12)))
                .thenReturn(1L);

        Habit result = service.completeToday(habit);

        assertThat(result.getCurrentStreak()).isEqualTo(2);
        assertThat(result.getLongestStreak()).isEqualTo(6);
        assertThat(result.getXpTotal()).isEqualTo(12);
    }

    @Test
    void completeToday_aWeeklyHabitDoneEarlierThisWeekIsNoOp() {
        // Done on Monday, and today is Thursday: the check must start on Monday, not today.
        Habit habit = weeklyHabit(3, 3, THIS_MONDAY);
        when(habitEntryRepository.existsByHabitAndCompletedDateGreaterThanEqual(habit, THIS_MONDAY)).thenReturn(true);

        Habit result = service.completeToday(habit);

        assertThat(result.getCurrentStreak()).isEqualTo(3);
        verify(habitRepository, never()).save(any());
        verify(habitEntryRepository, never()).save(any());
        verifyNoInteractions(achievementService);
    }

    @Test
    void completeToday_locksTheUserThenTheHabitAndReReadsTheHabit() {
        // The caller's copy: streak 1, last completed two days ago. Before the lock was taken, the
        // hourly reset zeroed that lapsed streak, so the row now says 0.
        Habit habit = dailyHabit(1, 1, TODAY.minusDays(2));
        doAnswer(inv -> {
            habit.setCurrentStreak(0);
            return null;
        }).when(entityManager).refresh(habit);
        when(habitEntryRepository.existsByHabitAndCompletedDateGreaterThanEqual(eq(habit), any())).thenReturn(false);

        Habit result = service.completeToday(habit);

        assertThat(result.getCurrentStreak()).isEqualTo(1);
        assertThat(result.getLastCompletedDate()).isEqualTo(TODAY);
        // The worker's lock order, user then habit, so the two modes cannot deadlock.
        InOrder order = inOrder(userRepository, habitRepository, entityManager);
        order.verify(userRepository).lockById(7L);
        order.verify(habitRepository).lockById(42L);
        order.verify(entityManager).refresh(habit);
        order.verify(habitRepository).save(habit);
    }

    @Test
    void completeToday_aCompletionThatCommittedMeanwhileIsSeenAfterTheLock() {
        // The caller's copy: streak 4, last completed yesterday. Another request completed the
        // habit today and committed while this one waited for the lock.
        Habit habit = dailyHabit(4, 4, TODAY.minusDays(1));
        doAnswer(inv -> {
            habit.setCurrentStreak(5);
            habit.setLongestStreak(5);
            habit.setLastCompletedDate(TODAY);
            return null;
        }).when(entityManager).refresh(habit);
        when(habitEntryRepository.existsByHabitAndCompletedDateGreaterThanEqual(eq(habit), any())).thenReturn(true);

        Habit result = service.completeToday(habit);

        assertThat(result.getCurrentStreak()).isEqualTo(5);
        verify(habitRepository, never()).save(any());
        verifyNoInteractions(achievementService);
    }

    @Test
    void completeToday_aHabitNotInThePersistenceContextIsLoadedFresh() {
        Habit detached = dailyHabit(4, 4, TODAY.minusDays(1));
        Habit current = dailyHabit(4, 4, TODAY.minusDays(1));
        when(entityManager.contains(detached)).thenReturn(false);
        when(entityManager.find(Habit.class, 42L)).thenReturn(current);
        when(habitEntryRepository.existsByHabitAndCompletedDateGreaterThanEqual(eq(current), any())).thenReturn(false);
        when(habitEntryRepository.dailyStreakEndingAt(42L, TODAY.minusDays(1))).thenReturn(4L);

        Habit result = service.completeToday(detached);

        assertThat(result).isSameAs(current);
        assertThat(result.getCurrentStreak()).isEqualTo(5);
        verify(entityManager, never()).refresh(any());
    }

    @Test
    void completeToday_aHabitDeletedMeanwhileIsNotFound() {
        Habit habit = dailyHabit(0, 0, null);
        when(habitRepository.lockById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeToday(habit))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Habit not found");
        verifyNoInteractions(habitEntryRepository, achievementService);
    }

    @Test
    void completeToday_alreadyCompletedForToday_isNoOpAndSkipsAchievementCheck() {
        Habit habit = dailyHabit(3, 3, TODAY);
        when(habitEntryRepository.existsByHabitAndCompletedDateGreaterThanEqual(eq(habit), any())).thenReturn(true);

        Habit result = service.completeToday(habit);

        assertThat(result.getCurrentStreak()).isEqualTo(3);
        verifyNoInteractions(achievementService);
        verify(habitRepository, never()).save(any());
    }

    @Test
    void recordCompletionOnly_writesEntryWithZeroXpAndDoesNotTouchStreak() {
        Habit habit = dailyHabit(2, 2, TODAY.minusDays(1));
        when(habitEntryRepository.existsByHabitAndCompletedDateGreaterThanEqual(eq(habit), any())).thenReturn(false);

        Habit result = service.recordCompletionOnly(habit);

        // Reward computation is deferred to the async worker, not done inline.
        assertThat(result.getCurrentStreak()).isEqualTo(2);
        assertThat(result.getXpTotal()).isEqualTo(0);
        verify(habitEntryRepository).save(argThat(entry -> entry.getXpEarned() == 0));
        verifyNoInteractions(achievementService);
        verify(habitRepository, never()).save(any());
    }

    @Test
    void recordCompletionOnly_writesAnOutboxEventDescribingTheCompletion() throws Exception {
        Habit habit = dailyHabit(0, 0, null);
        when(habitEntryRepository.existsByHabitAndCompletedDateGreaterThanEqual(eq(habit), any())).thenReturn(false);

        service.recordCompletionOnly(habit);

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(saved.capture());
        OutboxEvent event = saved.getValue();
        JsonNode message = new JsonMapper().readTree(event.getPayload());

        assertThat(event.getType()).isEqualTo("habit.completed");
        assertThat(event.getPublishedAt()).isNull();
        // The row id doubles as the event id the worker dedupes on.
        assertThat(message.get("eventId").asString()).isEqualTo(event.getId().toString());
        assertThat(message.get("userId").asLong()).isEqualTo(7L);
        assertThat(message.get("habitId").asLong()).isEqualTo(42L);
        assertThat(message.get("date").asString()).isEqualTo(TODAY.toString());
        assertThat(message.hasNonNull("occurredAt")).isTrue();
    }

    @Test
    void recordCompletionOnly_alreadyCompletedForPeriod_isNoOp() {
        Habit habit = dailyHabit(2, 2, TODAY);
        when(habitEntryRepository.existsByHabitAndCompletedDateGreaterThanEqual(eq(habit), any())).thenReturn(true);

        service.recordCompletionOnly(habit);

        verify(habitEntryRepository, never()).save(any());
        // No new completion, so no new event: repeated requests cannot flood the queue.
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void recordCompletionOnly_aWeeklyHabitDoneEarlierThisWeekIsNoOp() {
        // The async path relies on the same check: Monday's completion covers the whole week.
        Habit habit = weeklyHabit(0, 0, THIS_MONDAY);
        when(habitEntryRepository.existsByHabitAndCompletedDateGreaterThanEqual(habit, THIS_MONDAY)).thenReturn(true);

        service.recordCompletionOnly(habit);

        verify(habitEntryRepository, never()).save(any());
        verifyNoInteractions(outboxEventRepository);
    }
}
