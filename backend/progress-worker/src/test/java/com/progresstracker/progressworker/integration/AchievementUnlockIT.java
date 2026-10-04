package com.progresstracker.progressworker.integration;

import com.progresstracker.progressworker.service.CompletionProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** When each achievement unlocks, checked against the database after real reward transactions. */
class AchievementUnlockIT extends WorkerIntegrationTestBase {

    @Autowired
    private CompletionProcessor processor;

    @Test
    void theXpAchievementUnlocksWhenTheTotalAcrossAllOfAUsersHabitsReachesOneHundred() {
        long userId = insertUser();
        LocalDate today = LocalDate.now();
        // 80 XP earned earlier on another habit.
        long olderHabit = insertHabit(userId);
        jdbc.update("update habit set xp_total = 80 where id = ?", olderHabit);

        long first = completeNewHabit(userId, today);   // total 90
        assertThat(unlockedAchievements(userId)).containsExactly("FIRST_COMPLETION");

        long second = completeNewHabit(userId, today);  // total 100: the threshold
        assertThat(unlockedAchievements(userId)).containsExactly("FIRST_COMPLETION", "XP_100");

        completeNewHabit(userId, today);                // total 110: nothing new, nothing twice
        assertThat(unlockedAchievements(userId)).containsExactly("FIRST_COMPLETION", "XP_100");
        assertThat(xpTotal(first)).isEqualTo(10);
        assertThat(xpTotal(second)).isEqualTo(10);
    }

    @Test
    void theStreakAchievementUnlocksOnTheSeventhDayInARow() {
        long userId = insertUser();
        long habitId = insertHabit(userId);
        LocalDate today = LocalDate.now();
        for (int daysAgo = 5; daysAgo >= 1; daysAgo--) {
            insertEntry(habitId, today.minusDays(daysAgo), 10);
        }
        insertEntry(habitId, today, 0);

        processor.process(UUID.randomUUID(), userId, habitId, today, OffsetDateTime.now()); // day six
        assertThat(unlockedAchievements(userId)).doesNotContain("STREAK_7");

        // One more day at the start makes today the seventh in a row.
        long sevenDayHabit = insertHabit(userId);
        for (int daysAgo = 6; daysAgo >= 1; daysAgo--) {
            insertEntry(sevenDayHabit, today.minusDays(daysAgo), 10);
        }
        insertEntry(sevenDayHabit, today, 0);

        processor.process(UUID.randomUUID(), userId, sevenDayHabit, today, OffsetDateTime.now());
        assertThat(unlockedAchievements(userId)).contains("STREAK_7");
        assertThat(currentStreak(sevenDayHabit)).isEqualTo(7);
    }

    @Test
    void aWeeklyHabitCountsItsStreakInWeeks() {
        long userId = insertUser();
        long habitId = insertHabit(userId);
        jdbc.update("update habit set frequency = 'WEEKLY', goal_period = 'WEEKLY' where id = ?", habitId);
        LocalDate today = LocalDate.now();
        insertEntry(habitId, today.minusWeeks(2), 10);
        insertEntry(habitId, today.minusWeeks(1), 10);
        insertEntry(habitId, today, 0);

        processor.process(UUID.randomUUID(), userId, habitId, today, OffsetDateTime.now());

        assertThat(currentStreak(habitId)).isEqualTo(3);
        assertThat(xpEarned(habitId, today)).isEqualTo(14); // 10 + 2 for each week beyond the first
    }

    /** Creates a habit, records today's completion as the API would, and has the worker reward it. */
    private long completeNewHabit(long userId, LocalDate today) {
        long habitId = insertHabit(userId);
        insertEntry(habitId, today, 0);
        processor.process(UUID.randomUUID(), userId, habitId, today, OffsetDateTime.now());
        return habitId;
    }
}
