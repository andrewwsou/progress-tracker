package com.progresstracker.progressworker.service;

import com.progresstracker.progressworker.model.Achievement;
import com.progresstracker.progressworker.model.Habit;
import com.progresstracker.progressworker.model.User;
import com.progresstracker.progressworker.repository.AchievementRepository;
import com.progresstracker.progressworker.repository.HabitEntryRepository;
import com.progresstracker.progressworker.repository.HabitRepository;
import com.progresstracker.progressworker.repository.UserAchievementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** How many queries the per-event achievement check makes, and when it may remember the definitions. */
@ExtendWith(MockitoExtension.class)
class AchievementServiceTest {

    @Mock
    private AchievementRepository achievementRepository;

    @Mock
    private UserAchievementRepository userAchievementRepository;

    @Mock
    private HabitRepository habitRepository;

    @Mock
    private HabitEntryRepository habitEntryRepository;

    private AchievementService service;
    private User user;
    private Habit habit;

    @BeforeEach
    void setUp() {
        service = new AchievementService(
                achievementRepository, userAchievementRepository, habitRepository, habitEntryRepository);
        user = new User();
        user.setId(1L);
        habit = new Habit();
        habit.setLongestStreak(1);
        lenient().when(userAchievementRepository.insertIfAbsent(anyLong(), any(), any())).thenReturn(1);
    }

    private static List<Achievement> allDefinitions() {
        return List.of(
                new Achievement(AchievementService.FIRST_COMPLETION, "First Step", "", 1, "COMPLETION"),
                new Achievement(AchievementService.STREAK_7, "On a Roll", "", 7, "STREAK"),
                new Achievement(AchievementService.XP_100, "Level Up", "", 100, "XP"));
    }

    @Test
    void readsTheDefinitionsOnceAndThenKeepsThemInMemory() {
        when(achievementRepository.findAll()).thenReturn(allDefinitions());
        when(userAchievementRepository.findUnlockedCodes(1L)).thenReturn(Set.of());

        service.evaluateAndUnlock(user, habit);
        service.evaluateAndUnlock(user, habit);
        service.evaluateAndUnlock(user, habit);

        verify(achievementRepository, times(1)).findAll();
        verify(achievementRepository, never()).findByCode(anyString());
    }

    @Test
    void onceEverythingIsUnlockedTheCheckIsASingleQuery() {
        when(achievementRepository.findAll()).thenReturn(allDefinitions());
        when(userAchievementRepository.findUnlockedCodes(1L)).thenReturn(
                Set.of(AchievementService.FIRST_COMPLETION, AchievementService.STREAK_7, AchievementService.XP_100));

        service.evaluateAndUnlock(user, habit);

        verify(userAchievementRepository, times(1)).findUnlockedCodes(1L);
        verify(habitEntryRepository, never()).countByHabitUser(any());
        verify(habitRepository, never()).sumXpByUser(any());
        verify(userAchievementRepository, never()).insertIfAbsent(anyLong(), any(), any());
    }

    @Test
    void definitionsCreatedInThisTransactionAreNotRememberedUntilTheyAreCommitted() {
        // An empty database: the definitions are missing, so this call has to create them.
        when(achievementRepository.findAll()).thenReturn(List.of(), allDefinitions());
        when(achievementRepository.findByCode(anyString())).thenReturn(Optional.empty());
        when(userAchievementRepository.findUnlockedCodes(1L)).thenReturn(Set.of());

        service.evaluateAndUnlock(user, habit);
        verify(achievementRepository, times(3)).save(any(Achievement.class));

        // That transaction might still roll back, so the next event must read them again
        // rather than trust rows that may never have been committed.
        when(achievementRepository.findAll()).thenReturn(allDefinitions());
        service.evaluateAndUnlock(user, habit);
        service.evaluateAndUnlock(user, habit);

        // two reads during the first event (missing, then created), one for the second, none for the third
        verify(achievementRepository, times(3)).findAll();
    }
}
