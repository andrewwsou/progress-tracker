package com.progresstracker.progresstracker.automation;

import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.repository.HabitRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The service's part of the job is working out the two cut-off dates. Which rows those dates
 * select is the query's part, covered against a real database in StreakResetIT.
 */
@ExtendWith(MockitoExtension.class)
class StreakResetServiceTest {

    @Mock
    private HabitRepository habitRepository;

    @ParameterizedTest(name = "on {0}: daily cut-off {1}, weekly cut-off {2}")
    @CsvSource({
            // today,     yesterday,  Monday of last week
            "2026-07-13, 2026-07-12, 2026-07-06", // a Monday
            "2026-07-16, 2026-07-15, 2026-07-06", // a Thursday
            "2026-07-19, 2026-07-18, 2026-07-06", // a Sunday: still the same week
            "2026-07-20, 2026-07-19, 2026-07-13", // the next Monday: the window moves on
            "2027-01-01, 2026-12-31, 2026-12-21", // a Friday in the last ISO week of 2026 (week 53)
    })
    void passesTheRightCutOffDatesForAnyDayOfTheWeek(LocalDate today, LocalDate yesterday, LocalDate startOfLastWeek) {
        new StreakResetService(habitRepository).resetBrokenStreaks(today);

        verify(habitRepository).resetLapsedStreaks(yesterday, startOfLastWeek, Habit.Frequency.WEEKLY);
    }

    @Test
    void returnsHowManyStreaksTheDatabaseReset() {
        when(habitRepository.resetLapsedStreaks(any(), any(), any())).thenReturn(7);

        assertThat(new StreakResetService(habitRepository).resetBrokenStreaks(LocalDate.of(2026, 7, 16))).isEqualTo(7);
    }
}
