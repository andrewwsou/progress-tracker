package com.progresstracker.progressworker.service;

import com.progresstracker.progressworker.model.Habit;
import com.progresstracker.progressworker.model.HabitEntry;
import com.progresstracker.progressworker.model.User;
import com.progresstracker.progressworker.repository.HabitEntryRepository;
import com.progresstracker.progressworker.repository.HabitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompletionProcessorTest {

    @Mock
    private HabitRepository habitRepository;

    @Mock
    private HabitEntryRepository habitEntryRepository;

    @Mock
    private AchievementService achievementService;

    @Mock
    private EmailService emailService;

    private CompletionProcessor processor;

    // In-memory stand-in for the habit_entries unique (habit_id, completed_date) row,
    // so repeated process() calls behave like repeated SQS delivery against real storage.
    private final Map<LocalDate, HabitEntry> storedEntries = new HashMap<>();

    @BeforeEach
    void setUp() {
        processor = new CompletionProcessor(habitRepository, habitEntryRepository, achievementService, emailService);
    }

    private Habit habitWithUser(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setEmail("u@example.com");

        Habit habit = new Habit();
        habit.setId(100L);
        habit.setUser(user);
        habit.setName("Read");
        habit.setFrequency(Habit.Frequency.DAILY);
        habit.setCurrentStreak(0);
        habit.setLongestStreak(0);
        return habit;
    }

    private void stubEntryPersistence(Habit habit) {
        when(habitEntryRepository.findByHabitAndCompletedDate(eq(habit), any()))
                .thenAnswer(inv -> Optional.ofNullable(storedEntries.get(inv.getArgument(1, LocalDate.class))));

        when(habitEntryRepository.save(any(HabitEntry.class))).thenAnswer(inv -> {
            HabitEntry entry = inv.getArgument(0);
            storedEntries.put(entry.getCompletedDate(), entry);
            return entry;
        });

        when(habitEntryRepository.existsByHabitAndCompletedDate(eq(habit), any()))
                .thenAnswer(inv -> storedEntries.containsKey(inv.getArgument(1, LocalDate.class)));

        when(habitRepository.save(any(Habit.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void process_firstDelivery_awardsXpAndUpdatesStreak() {
        Habit habit = habitWithUser(1L);
        when(habitRepository.findById(100L)).thenReturn(Optional.of(habit));
        stubEntryPersistence(habit);

        processor.process(1L, 100L, LocalDate.now());

        assertThat(habit.getXpTotal()).isEqualTo(10);
        assertThat(habit.getCurrentStreak()).isEqualTo(1);
        verify(achievementService, times(1)).evaluateAndUnlock(eq(habit.getUser()), eq(habit));
        verify(emailService, times(1)).queueCompletionEmail(eq(habit.getUser()), eq("Read"));
    }

    @Test
    void process_redeliveredMessageForSameCompletion_isIdempotent() {
        // SQS is at-least-once: the same completion message can be delivered twice
        // (e.g. if the worker crashes after processing but before deleting the message).
        Habit habit = habitWithUser(1L);
        when(habitRepository.findById(100L)).thenReturn(Optional.of(habit));
        stubEntryPersistence(habit);

        LocalDate date = LocalDate.now();
        processor.process(1L, 100L, date);
        processor.process(1L, 100L, date); // redelivery

        assertThat(habit.getXpTotal()).isEqualTo(10); // not 20
        assertThat(habit.getCurrentStreak()).isEqualTo(1);
        verify(achievementService, times(1)).evaluateAndUnlock(any(), any());
        verify(emailService, times(1)).queueCompletionEmail(any(), any());
    }

    @Test
    void process_habitBelongsToDifferentUser_throwsAndSkipsRewardGrant() {
        Habit habit = habitWithUser(1L);
        when(habitRepository.findById(100L)).thenReturn(Optional.of(habit));

        assertThatThrownBy(() -> processor.process(999L, 100L, LocalDate.now()))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(achievementService, emailService);
    }
}
