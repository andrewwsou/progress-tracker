package com.progresstracker.progresstracker.automation;

import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.model.User;
import com.progresstracker.progresstracker.repository.HabitRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreakResetServiceTest {

    @Mock
    private HabitRepository habitRepository;

    private final LocalDate today = LocalDate.of(2026, 7, 16); // a Thursday

    private Habit habit(Habit.Frequency frequency, int streak, LocalDate lastCompleted) {
        Habit h = new Habit(new User("u@example.com", "hash"), "Read", "desc", frequency);
        h.setId(1L);
        h.setCurrentStreak(streak);
        h.setLastCompletedDate(lastCompleted);
        return h;
    }

    private StreakResetService service() {
        return new StreakResetService(habitRepository);
    }

    @Test
    void dailyHabit_completedYesterday_streakNotBroken() {
        Habit h = habit(Habit.Frequency.DAILY, 5, today.minusDays(1));
        when(habitRepository.findAll()).thenReturn(List.of(h));

        int resetCount = service().resetBrokenStreaks(today);

        assertThat(resetCount).isZero();
        assertThat(h.getCurrentStreak()).isEqualTo(5);
    }

    @Test
    void dailyHabit_missedTwoDays_streakIsReset() {
        Habit h = habit(Habit.Frequency.DAILY, 5, today.minusDays(2));
        when(habitRepository.findAll()).thenReturn(List.of(h));

        int resetCount = service().resetBrokenStreaks(today);

        assertThat(resetCount).isEqualTo(1);
        assertThat(h.getCurrentStreak()).isZero();
    }

    @Test
    void weeklyHabit_completedLastWeek_streakNotBroken() {
        Habit h = habit(Habit.Frequency.WEEKLY, 3, today.minusWeeks(1));
        when(habitRepository.findAll()).thenReturn(List.of(h));

        int resetCount = service().resetBrokenStreaks(today);

        assertThat(resetCount).isZero();
        assertThat(h.getCurrentStreak()).isEqualTo(3);
    }

    @Test
    void weeklyHabit_missedTwoWeeks_streakIsReset() {
        Habit h = habit(Habit.Frequency.WEEKLY, 3, today.minusWeeks(2));
        when(habitRepository.findAll()).thenReturn(List.of(h));

        int resetCount = service().resetBrokenStreaks(today);

        assertThat(resetCount).isEqualTo(1);
        assertThat(h.getCurrentStreak()).isZero();
    }

    @Test
    void habitWithNoCurrentStreak_isSkippedEntirely() {
        Habit h = habit(Habit.Frequency.DAILY, 0, today.minusDays(30));
        when(habitRepository.findAll()).thenReturn(List.of(h));

        int resetCount = service().resetBrokenStreaks(today);

        assertThat(resetCount).isZero();
        verify0Saves();
    }

    private void verify0Saves() {
        org.mockito.Mockito.verify(habitRepository, org.mockito.Mockito.never()).save(any());
    }
}
