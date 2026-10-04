package com.progresstracker.progresstracker.automation;

import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.model.User;
import com.progresstracker.progresstracker.repository.HabitEntryRepository;
import com.progresstracker.progresstracker.repository.HabitRepository;
import com.progresstracker.progresstracker.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeeklySummaryServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private HabitRepository habitRepository;

    @Mock
    private HabitEntryRepository habitEntryRepository;

    private WeeklySummaryService service;

    private final LocalDate weekStart = LocalDate.of(2026, 7, 6); // a Monday

    @BeforeEach
    void setUp() {
        service = new WeeklySummaryService(userRepository, habitRepository, habitEntryRepository);
    }

    @Test
    void aggregatesCompletionsAndXpAcrossAllHabitsForEachUser() {
        User user = new User("u@example.com", "hash");
        user.setId(1L);

        Habit reading = new Habit(user, "Read", "desc", Habit.Frequency.DAILY);
        Habit gym = new Habit(user, "Gym", "desc", Habit.Frequency.DAILY);

        when(userRepository.findAll()).thenReturn(List.of(user));
        when(habitRepository.findByUser(user)).thenReturn(List.of(reading, gym));

        when(habitEntryRepository.countByHabitAndCompletedDateBetween(eq(reading), any(), any())).thenReturn(5L);
        when(habitEntryRepository.sumXpByHabitAndCompletedDateBetween(eq(reading), any(), any())).thenReturn(70L);
        when(habitEntryRepository.countByHabitAndCompletedDateBetween(eq(gym), any(), any())).thenReturn(3L);
        when(habitEntryRepository.sumXpByHabitAndCompletedDateBetween(eq(gym), any(), any())).thenReturn(36L);

        List<WeeklySummary> summaries = service.generateSummaries(weekStart);

        assertThat(summaries).hasSize(1);
        WeeklySummary summary = summaries.get(0);
        assertThat(summary.userId()).isEqualTo(1L);
        assertThat(summary.habitCount()).isEqualTo(2);
        assertThat(summary.completions()).isEqualTo(8);
        assertThat(summary.xpEarned()).isEqualTo(106);
        assertThat(summary.weekStart()).isEqualTo(weekStart);
        assertThat(summary.weekEnd()).isEqualTo(weekStart.plusDays(6));
    }

    @Test
    void userWithNoHabits_getsZeroedSummaryNotSkipped() {
        User user = new User("empty@example.com", "hash");
        user.setId(2L);

        when(userRepository.findAll()).thenReturn(List.of(user));
        when(habitRepository.findByUser(user)).thenReturn(List.of());

        List<WeeklySummary> summaries = service.generateSummaries(weekStart);

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).completions()).isZero();
        assertThat(summaries.get(0).xpEarned()).isZero();
    }
}
