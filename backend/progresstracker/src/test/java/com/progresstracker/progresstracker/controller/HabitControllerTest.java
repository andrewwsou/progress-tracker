package com.progresstracker.progresstracker.controller;

import com.progresstracker.progresstracker.dto.HabitResponse;
import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.model.User;
import com.progresstracker.progresstracker.repository.HabitEntryRepository;
import com.progresstracker.progresstracker.repository.HabitRepository;
import com.progresstracker.progresstracker.repository.UserRepository;
import com.progresstracker.progresstracker.service.HabitProgressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Covers the fix for a real race condition found via concurrent load testing:
 * two requests completing the same habit at once can both pass the
 * "already completed?" check before either commits, so the loser hits the
 * (habit_id, completed_date) unique constraint instead of the app-level
 * check. That must surface as a normal 200 (current state), not a 500.
 */
@ExtendWith(MockitoExtension.class)
class HabitControllerTest {

    @Mock
    private HabitRepository habitRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private HabitProgressService habitProgressService;

    @Mock
    private HabitEntryRepository habitEntryRepository;

    @Mock
    private Authentication authentication;

    private HabitController controller;

    @BeforeEach
    void setUp() {
        controller = new HabitController(habitRepository, userRepository, habitProgressService, habitEntryRepository);
        ReflectionTestUtils.setField(controller, "queueEnabled", false);
    }

    private Habit habitOwnedBy(User user) {
        Habit habit = new Habit(user, "Read", "desc", Habit.Frequency.DAILY);
        habit.setId(1L);
        return habit;
    }

    @Test
    void completeHabit_losesRaceOnUniqueConstraint_returnsCurrentStateInsteadOfFailing() {
        User user = new User("u@example.com", "hash");
        user.setId(1L);
        Habit habit = habitOwnedBy(user);

        when(authentication.getPrincipal()).thenReturn(user);
        when(habitRepository.findById(1L)).thenReturn(Optional.of(habit));
        when(habitProgressService.completeToday(habit)).thenThrow(new DataIntegrityViolationException("duplicate key"));

        Habit winningState = habitOwnedBy(user);
        winningState.setCurrentStreak(1);
        winningState.setXpTotal(10);
        // Second lookup, after the race is lost, re-fetches the winner's committed state.
        when(habitRepository.findById(1L)).thenReturn(Optional.of(habit), Optional.of(winningState));
        when(habitProgressService.alreadyCompletedForPeriod(eq(winningState), any())).thenReturn(true);

        HabitResponse result = controller.completeHabit(1L, authentication);

        assertThat(result.currentStreak()).isEqualTo(1);
        assertThat(result.xpTotal()).isEqualTo(10);
    }

    @Test
    void completeHabit_constraintFailureThatLeftNoCompletion_isNotReportedAsSuccess() {
        User user = new User("u@example.com", "hash");
        user.setId(1L);
        Habit habit = habitOwnedBy(user);
        DataIntegrityViolationException failure = new DataIntegrityViolationException("some other constraint");

        when(authentication.getPrincipal()).thenReturn(user);
        when(habitRepository.findById(1L)).thenReturn(Optional.of(habit));
        when(habitProgressService.completeToday(habit)).thenThrow(failure);
        // The transaction rolled back, so there is no completion for today.
        when(habitProgressService.alreadyCompletedForPeriod(eq(habit), any())).thenReturn(false);

        assertThatThrownBy(() -> controller.completeHabit(1L, authentication)).isSameAs(failure);
    }
}
