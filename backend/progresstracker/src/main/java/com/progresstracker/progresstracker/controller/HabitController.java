package com.progresstracker.progresstracker.controller;

import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.model.User;
import com.progresstracker.progresstracker.repository.HabitEntryRepository;
import com.progresstracker.progresstracker.repository.HabitRepository;
import com.progresstracker.progresstracker.repository.UserRepository;
import com.progresstracker.progresstracker.service.CompletionQueueService;
import com.progresstracker.progresstracker.service.HabitProgressService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

@RestController
@RequestMapping("/api/habits")
@CrossOrigin(origins = "http://localhost:5173")
public class HabitController {

    private final HabitRepository habitRepository;
    private final UserRepository userRepository;
    private final HabitProgressService habitProgressService;
    private final HabitEntryRepository habitEntryRepository;
    private final CompletionQueueService completionQueueService;

    @Value("${queue.enabled:true}")
    private boolean queueEnabled;

    public HabitController(HabitRepository habitRepository,
                           UserRepository userRepository,
                           HabitProgressService habitProgressService,
                           HabitEntryRepository habitEntryRepository,
                           CompletionQueueService completionQueueService) {
        this.habitRepository = habitRepository;
        this.userRepository = userRepository;
        this.habitProgressService = habitProgressService;
        this.habitEntryRepository = habitEntryRepository;
        this.completionQueueService = completionQueueService;
    }

    private User requireUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return user;
    }

    @GetMapping
    public List<Habit> getAllHabits(Authentication authentication) {
        User user = requireUser(authentication);
        List<Habit> habits = habitRepository.findByUser(user);
        for (Habit h : habits) {
            applyProgressFields(h);
        }
        return habits;
    }

    @PostMapping
    public Habit createHabit(@RequestBody Habit habit, Authentication authentication) {
        User user = requireUser(authentication);
        habit.setUser(user);
        applyGoalDefaults(habit);
        Habit saved = habitRepository.save(habit);
        applyProgressFields(saved);
        return saved;
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id, Authentication authentication) {
        User user = requireUser(authentication);

        Habit habit = habitRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Habit not found"));

        if (!habit.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete someone else's habit");
        }

        habitEntryRepository.deleteByHabitId(id);
        habitRepository.delete(habit);
    }

    @PutMapping("/{id}")
    public Habit update(@PathVariable Long id,
                        @RequestBody Habit updated,
                        Authentication authentication) {
        User user = requireUser(authentication);

        return habitRepository.findById(id)
                .map(existing -> {
                    if (!existing.getUser().getId().equals(user.getId())) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot edit someone else's habit");
                    }
                    existing.setName(updated.getName());
                    existing.setDescription(updated.getDescription());
                    existing.setFrequency(updated.getFrequency());
                    if (updated.getGoalTargetCount() != null) {
                        existing.setGoalTargetCount(updated.getGoalTargetCount());
                    }
                    if (updated.getGoalPeriod() != null) {
                        existing.setGoalPeriod(updated.getGoalPeriod());
                    }
                    applyGoalDefaults(existing);
                    Habit saved = habitRepository.save(existing);
                    applyProgressFields(saved);
                    return saved;
                })
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Habit not found")
                );
    }

    @PostMapping("/{id}/complete")
    public Habit completeHabit(@PathVariable Long id, Authentication authentication) {
        User user = requireUser(authentication);

        Habit habit = habitRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Habit not found"));

        if (habit.getUser() == null || !habit.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot complete someone else's habit");
        }

        Habit updated;
        System.out.println("DEBUG queueEnabled=" + queueEnabled);
        System.out.println("DEBUG queueEnabled=" + queueEnabled);
        System.out.println("DEBUG QUEUE_SQS_URL env=" + System.getenv("QUEUE_SQS_URL"));

        if (queueEnabled) {
            updated = habitProgressService.recordCompletionOnly(habit);
            completionQueueService.enqueueCompletion(user.getId(), habit.getId(), LocalDate.now());
        } else {
            updated = habitProgressService.completeToday(habit);
        }

        applyProgressFields(updated);
        return updated;
    }

    private void applyGoalDefaults(Habit habit) {
        if (habit.getGoalTargetCount() == null || habit.getGoalTargetCount() <= 0) {
            habit.setGoalTargetCount(1);
        }
        if (habit.getGoalPeriod() == null) {
            if (habit.getFrequency() == Habit.Frequency.WEEKLY) {
                habit.setGoalPeriod(Habit.GoalPeriod.WEEKLY);
            } else {
                habit.setGoalPeriod(Habit.GoalPeriod.DAILY);
            }
        }
    }

    private void applyProgressFields(Habit habit) {
        applyGoalDefaults(habit);

        LocalDate today = LocalDate.now();
        int count;
        if (habit.getGoalPeriod() == Habit.GoalPeriod.WEEKLY) {
            LocalDate start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate end = start.plusDays(6);
            count = (int) habitEntryRepository.countByHabitAndCompletedDateBetween(habit, start, end);
        } else {
            count = habitEntryRepository.findByHabitAndCompletedDate(habit, today).isPresent() ? 1 : 0;
        }

        habit.setProgressCount(count);
        habit.setProgressTargetCount(habit.getGoalTargetCount());
    }
}
