package com.progresstracker.progressworker.service;

import com.progresstracker.progressworker.model.Achievement;
import com.progresstracker.progressworker.model.Habit;
import com.progresstracker.progressworker.model.User;
import com.progresstracker.progressworker.repository.AchievementRepository;
import com.progresstracker.progressworker.repository.HabitEntryRepository;
import com.progresstracker.progressworker.repository.HabitRepository;
import com.progresstracker.progressworker.repository.UserAchievementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AchievementService {

    public static final String FIRST_COMPLETION = "FIRST_COMPLETION";
    public static final String STREAK_7 = "STREAK_7";
    public static final String XP_100 = "XP_100";

    private static final Set<String> CODES = Set.of(FIRST_COMPLETION, STREAK_7, XP_100);

    private final AchievementRepository achievementRepository;
    private final UserAchievementRepository userAchievementRepository;
    private final HabitRepository habitRepository;
    private final HabitEntryRepository habitEntryRepository;

    /**
     * The achievement definitions by code. They are fixed rows that do not change while the
     * worker runs, so they are read once instead of on every event. Only ever set from rows that
     * were already committed (see {@link #definitions()}).
     */
    private volatile Map<String, Achievement> cachedDefinitions;

    public AchievementService(
            AchievementRepository achievementRepository,
            UserAchievementRepository userAchievementRepository,
            HabitRepository habitRepository,
            HabitEntryRepository habitEntryRepository
    ) {
        this.achievementRepository = achievementRepository;
        this.userAchievementRepository = userAchievementRepository;
        this.habitRepository = habitRepository;
        this.habitEntryRepository = habitEntryRepository;
    }

    @Transactional
    public void ensureDefaultAchievements() {
        upsert(FIRST_COMPLETION, "First Step", "Complete a habit for the first time.", 1, "COMPLETION");
        upsert(STREAK_7, "On a Roll", "Reach a 7-day streak on any habit.", 7, "STREAK");
        upsert(XP_100, "Level Up", "Earn 100 total XP across all habits.", 100, "XP");
    }

    private void upsert(String code, String name, String description, int threshold, String type) {
        Achievement a = achievementRepository.findByCode(code).orElse(null);
        if (a == null) {
            achievementRepository.save(new Achievement(code, name, description, threshold, type));
            return;
        }

        boolean changed = false;

        if (!name.equals(a.getName())) {
            a.setName(name);
            changed = true;
        }
        if (!description.equals(a.getDescription())) {
            a.setDescription(description);
            changed = true;
        }
        if (a.getThreshold() == null || a.getThreshold() != threshold) {
            a.setThreshold(threshold);
            changed = true;
        }
        if (a.getType() == null || !type.equals(a.getType())) {
            a.setType(type);
            changed = true;
        }

        if (changed) {
            achievementRepository.save(a);
        }
    }

    /**
     * Unlocks whatever the user has newly earned. This runs for every completion, so it is kept
     * to as few queries as possible: one to read what the user already has, and then one more
     * only for each achievement that is still locked and needs a number from the database.
     */
    @Transactional
    public List<Achievement> evaluateAndUnlock(User user, Habit justUpdatedHabit) {
        Map<String, Achievement> definitions = definitions();
        Set<String> unlocked = userAchievementRepository.findUnlockedCodes(user.getId());

        List<Achievement> newlyUnlocked = new ArrayList<>();

        Achievement first = definitions.get(FIRST_COMPLETION);
        if (!unlocked.contains(FIRST_COMPLETION)
                && habitEntryRepository.countByHabitUser(user) >= first.getThreshold()) {
            if (unlock(user, first)) newlyUnlocked.add(first);
        }

        // The longest streak, not the current one: it only ever grows, so the result does not
        // depend on the order completions were processed in.
        Achievement streak7 = definitions.get(STREAK_7);
        if (!unlocked.contains(STREAK_7)
                && justUpdatedHabit.getLongestStreak() >= streak7.getThreshold()) {
            if (unlock(user, streak7)) newlyUnlocked.add(streak7);
        }

        Achievement xp100 = definitions.get(XP_100);
        if (!unlocked.contains(XP_100)
                && habitRepository.sumXpByUser(user) >= xp100.getThreshold()) {
            if (unlock(user, xp100)) newlyUnlocked.add(xp100);
        }

        return newlyUnlocked;
    }

    /**
     * The definitions, read from the database the first time and from memory after that.
     *
     * Normally the API has already created them. If they are missing (the worker was started
     * against an empty database), they are created here, but not remembered on this call: they
     * belong to a transaction that may still roll back, and remembering rows that were never
     * committed would poison every later event. The next event finds them committed and caches them.
     */
    private Map<String, Achievement> definitions() {
        Map<String, Achievement> cached = cachedDefinitions;
        if (cached != null) {
            return cached;
        }

        Map<String, Achievement> found = loadDefinitions();
        if (found.keySet().containsAll(CODES)) {
            cachedDefinitions = found;
            return found;
        }

        ensureDefaultAchievements();
        return loadDefinitions();
    }

    private Map<String, Achievement> loadDefinitions() {
        return achievementRepository.findAll().stream()
                .collect(Collectors.toUnmodifiableMap(Achievement::getCode, Function.identity()));
    }

    /** Safe to race: unlocking something already unlocked is a no-op, never a constraint failure. */
    private boolean unlock(User user, Achievement achievement) {
        return userAchievementRepository.insertIfAbsent(user.getId(), achievement.getId(), LocalDateTime.now()) == 1;
    }
}
