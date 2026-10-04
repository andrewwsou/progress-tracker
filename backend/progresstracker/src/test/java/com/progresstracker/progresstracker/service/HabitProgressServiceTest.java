package com.progresstracker.progresstracker.service;

import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.model.HabitEntry;
import com.progresstracker.progresstracker.model.User;
import com.progresstracker.progresstracker.repository.HabitEntryRepository;
import com.progresstracker.progresstracker.repository.HabitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HabitProgressServiceTest {

    @Mock
    private HabitRepository habitRepository;

    @Mock
    private HabitEntryRepository habitEntryRepository;

    @Mock
    private AchievementService achievementService;

    private HabitProgressService service;

    @BeforeEach
    void setUp() {
        service = new HabitProgressService(habitRepository, habitEntryRepository, achievementService);
        lenient().when(habitRepository.save(any(Habit.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Habit dailyHabit(int currentStreak, int longestStreak, LocalDate lastCompleted) {
        Habit habit = new Habit(new User("u@example.com", "hash"), "Read", "desc", Habit.Frequency.DAILY);
        habit.setCurrentStreak(currentStreak);
        habit.setLongestStreak(longestStreak);
        habit.setLastCompletedDate(lastCompleted);
        return habit;
    }

    @Test
    void completeToday_firstEverCompletion_startsStreakAtOne() {
        Habit habit = dailyHabit(0, 0, null);
        when(habitEntryRepository.findByHabitAndCompletedDate(eq(habit), any())).thenReturn(Optional.empty());

        Habit result = service.completeToday(habit);

        assertThat(result.getCurrentStreak()).isEqualTo(1);
        assertThat(result.getLongestStreak()).isEqualTo(1);
        assertThat(result.getXpTotal()).isEqualTo(10);
        verify(achievementService).evaluateAndUnlock(eq(habit.getUser()), eq(habit));
    }

    @Test
    void completeToday_consecutiveDay_incrementsStreakAndXp() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        Habit habit = dailyHabit(4, 4, yesterday);
        when(habitEntryRepository.findByHabitAndCompletedDate(eq(habit), any())).thenReturn(Optional.empty());

        Habit result = service.completeToday(habit);

        assertThat(result.getCurrentStreak()).isEqualTo(5);
        assertThat(result.getLongestStreak()).isEqualTo(5);
        assertThat(result.getXpTotal()).isEqualTo(18); // 10 + min(20, (5-1)*2)
    }

    @Test
    void completeToday_afterGapInDays_resetsStreakToOne() {
        LocalDate threeDaysAgo = LocalDate.now().minusDays(3);
        Habit habit = dailyHabit(9, 9, threeDaysAgo);
        when(habitEntryRepository.findByHabitAndCompletedDate(eq(habit), any())).thenReturn(Optional.empty());

        Habit result = service.completeToday(habit);

        assertThat(result.getCurrentStreak()).isEqualTo(1);
        assertThat(result.getLongestStreak()).isEqualTo(9); // longest streak is never lowered
    }

    @Test
    void completeToday_alreadyCompletedForToday_isNoOpAndSkipsAchievementCheck() {
        Habit habit = dailyHabit(3, 3, LocalDate.now());
        when(habitEntryRepository.findByHabitAndCompletedDate(eq(habit), any()))
                .thenReturn(Optional.of(new HabitEntry(habit, LocalDate.now(), 10)));

        Habit result = service.completeToday(habit);

        assertThat(result.getCurrentStreak()).isEqualTo(3);
        verifyNoInteractions(achievementService);
        verify(habitRepository, never()).save(any());
    }

    @Test
    void recordCompletionOnly_writesEntryWithZeroXpAndDoesNotTouchStreak() {
        Habit habit = dailyHabit(2, 2, LocalDate.now().minusDays(1));
        when(habitEntryRepository.findByHabitAndCompletedDate(eq(habit), any())).thenReturn(Optional.empty());

        Habit result = service.recordCompletionOnly(habit);

        // Reward computation is deferred to the async worker, not done inline.
        assertThat(result.getCurrentStreak()).isEqualTo(2);
        assertThat(result.getXpTotal()).isEqualTo(0);
        verify(habitEntryRepository).save(argThat(entry -> entry.getXpEarned() == 0));
        verifyNoInteractions(achievementService);
        verify(habitRepository, never()).save(any());
    }

    @Test
    void recordCompletionOnly_alreadyCompletedForPeriod_isNoOp() {
        Habit habit = dailyHabit(2, 2, LocalDate.now());
        when(habitEntryRepository.findByHabitAndCompletedDate(eq(habit), any()))
                .thenReturn(Optional.of(new HabitEntry(habit, LocalDate.now(), 0)));

        service.recordCompletionOnly(habit);

        verify(habitEntryRepository, never()).save(any());
    }
}
