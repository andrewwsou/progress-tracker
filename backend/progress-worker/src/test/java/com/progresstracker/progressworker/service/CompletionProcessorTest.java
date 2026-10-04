package com.progresstracker.progressworker.service;

import com.progresstracker.progressworker.repository.ProcessedEventRepository;
import com.progresstracker.progressworker.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompletionProcessorTest {

    @Mock
    private HabitRepository habitRepository;

    @Mock
    private HabitEntryRepository habitEntryRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

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
        processor = new CompletionProcessor(habitRepository, habitEntryRepository, userRepository,
                processedEventRepository, achievementService, emailService);
        // By default every event id is new and every user exists.
        lenient().when(processedEventRepository.insertIfAbsent(any(), any())).thenReturn(1);
        lenient().when(userRepository.findByIdForUpdate(any())).thenAnswer(inv -> {
            User user = new User();
            user.setId(inv.getArgument(0, Long.class));
            user.setEmail("u@example.com");
            return Optional.of(user);
        });
    }

    private boolean process(Long userId, Long habitId, LocalDate date) {
        return processor.process(UUID.randomUUID(), userId, habitId, date, OffsetDateTime.now());
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

        // Stands in for the streak query: count stored days backwards from the given date.
        when(habitEntryRepository.dailyStreakEndingAt(eq(habit.getId()), any()))
                .thenAnswer(inv -> {
                    LocalDate day = inv.getArgument(1, LocalDate.class);
                    long streak = 0;
                    while (storedEntries.containsKey(day)) {
                        streak++;
                        day = day.minusDays(1);
                    }
                    return streak;
                });

        // Stands in for the reward UPDATE: the same rules, applied to the in-memory habit.
        when(habitRepository.applyReward(eq(habit.getId()), any(LocalDate.class), anyInt(), anyInt())).thenAnswer(inv -> {
            LocalDate date = inv.getArgument(1);
            int streak = inv.getArgument(2);
            int xp = inv.getArgument(3);
            if (habit.getLastCompletedDate() == null || !date.isBefore(habit.getLastCompletedDate())) {
                habit.setCurrentStreak(streak);
                habit.setLastCompletedDate(date);
            }
            habit.setLongestStreak(Math.max(habit.getLongestStreak(), streak));
            habit.setXpTotal(habit.getXpTotal() + xp);
            return 1;
        });
    }

    @Test
    void process_firstDelivery_awardsXpAndUpdatesStreak() {
        Habit habit = habitWithUser(1L);
        when(habitRepository.findById(100L)).thenReturn(Optional.of(habit));
        stubEntryPersistence(habit);

        boolean applied = process(1L, 100L, LocalDate.now());

        assertThat(applied).isTrue();
        assertThat(habit.getXpTotal()).isEqualTo(10);
        assertThat(habit.getCurrentStreak()).isEqualTo(1);
        verify(achievementService, times(1)).evaluateAndUnlock(any(), eq(habit));
        verify(emailService, times(1)).queueCompletionEmail(any(), eq("Read"));
    }

    @Test
    void process_habitNoLongerExists_isANoOpRatherThanAFailure() {
        when(habitRepository.findById(100L)).thenReturn(Optional.empty());

        boolean applied = process(1L, 100L, LocalDate.now());

        // Returning normally lets the event be recorded as handled and the message deleted,
        // instead of being retried five times and landing in the dead-letter queue.
        assertThat(applied).isFalse();
        verifyNoInteractions(habitEntryRepository, achievementService, emailService);
    }

    @Test
    void process_eventIdAlreadyRecorded_doesNothingAtAll() {
        // The inbox already holds this event id: another delivery of it was handled before.
        when(processedEventRepository.insertIfAbsent(any(), any())).thenReturn(0);

        boolean applied = process(1L, 100L, LocalDate.now());

        assertThat(applied).isFalse();
        verifyNoInteractions(userRepository, habitRepository, habitEntryRepository, achievementService, emailService);
    }

    @Test
    void process_olderDayHandledAfterANewerOne_doesNotRewindTheCurrentStreak() {
        Habit habit = habitWithUser(1L);
        when(habitRepository.findById(100L)).thenReturn(Optional.of(habit));
        stubEntryPersistence(habit);
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        // Both days were completed; the API wrote both rows before either event was handled.
        storedEntries.put(yesterday, entryFor(habit, yesterday));
        storedEntries.put(today, entryFor(habit, today));

        process(1L, 100L, today);      // streak 2, earns 12
        process(1L, 100L, yesterday);  // arrives late: streak 1, earns 10

        assertThat(habit.getCurrentStreak()).isEqualTo(2);
        assertThat(habit.getLastCompletedDate()).isEqualTo(today);
        assertThat(habit.getLongestStreak()).isEqualTo(2);
        assertThat(habit.getXpTotal()).isEqualTo(22);
    }

    private HabitEntry entryFor(Habit habit, LocalDate date) {
        HabitEntry entry = new HabitEntry();
        entry.setHabit(habit);
        entry.setCompletedDate(date);
        entry.setXpEarned(0);
        return entry;
    }

    @Test
    void process_redeliveredMessageForSameCompletion_isIdempotent() {
        // SQS is at-least-once: the same completion message can be delivered twice
        // (e.g. if the worker crashes after processing but before deleting the message).
        Habit habit = habitWithUser(1L);
        when(habitRepository.findById(100L)).thenReturn(Optional.of(habit));
        stubEntryPersistence(habit);

        LocalDate date = LocalDate.now();
        process(1L, 100L, date);
        process(1L, 100L, date); // the same completion again, under a different event id

        assertThat(habit.getXpTotal()).isEqualTo(10); // not 20
        assertThat(habit.getCurrentStreak()).isEqualTo(1);
        verify(achievementService, times(1)).evaluateAndUnlock(any(), any());
        verify(emailService, times(1)).queueCompletionEmail(any(), any());
    }

    @Test
    void process_habitBelongsToDifferentUser_throwsAndSkipsRewardGrant() {
        Habit habit = habitWithUser(1L);
        when(habitRepository.findById(100L)).thenReturn(Optional.of(habit));

        assertThatThrownBy(() -> process(999L, 100L, LocalDate.now()))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(achievementService, emailService);
    }
}
