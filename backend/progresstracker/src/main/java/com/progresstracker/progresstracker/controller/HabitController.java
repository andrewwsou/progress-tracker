package com.progresstracker.progresstracker.controller;

import com.progresstracker.progresstracker.dto.HabitRequest;
import com.progresstracker.progresstracker.dto.HabitResponse;
import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.model.User;
import com.progresstracker.progresstracker.repository.HabitEntryRepository;
import com.progresstracker.progresstracker.repository.HabitRepository;
import com.progresstracker.progresstracker.repository.UserRepository;
import com.progresstracker.progresstracker.service.HabitProgressService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
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
@Tag(name = "Habits")
public class HabitController {

    private static final Logger log = LoggerFactory.getLogger(HabitController.class);

    private final HabitRepository habitRepository;
    private final UserRepository userRepository;
    private final HabitProgressService habitProgressService;
    private final HabitEntryRepository habitEntryRepository;

    @Value("${queue.enabled:false}")
    private boolean queueEnabled;

    public HabitController(HabitRepository habitRepository,
                           UserRepository userRepository,
                           HabitProgressService habitProgressService,
                           HabitEntryRepository habitEntryRepository) {
        this.habitRepository = habitRepository;
        this.userRepository = userRepository;
        this.habitProgressService = habitProgressService;
        this.habitEntryRepository = habitEntryRepository;
    }

    @GetMapping
    public List<HabitResponse> getAllHabits(Authentication authentication) {
        User user = requireUser(authentication);
        return habitRepository.findByUser(user).stream().map(this::toResponse).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HabitResponse createHabit(@Valid @RequestBody HabitRequest request, Authentication authentication) {
        User user = requireUser(authentication);

        // Built field by field from the request. The id, the owner, and the reward state never
        // come from the client, so a request cannot overwrite or take over an existing habit.
        Habit habit = new Habit(user, request.name(), request.description(), request.frequency());
        habit.setGoalTargetCount(request.goalTargetCount());
        habit.setGoalPeriod(request.goalPeriod());
        applyGoalDefaults(habit);

        return toResponse(habitRepository.save(habit));
    }

    @PutMapping("/{id}")
    @Transactional
    public HabitResponse update(@PathVariable Long id,
                                @Valid @RequestBody HabitRequest request,
                                Authentication authentication) {
        Habit habit = requireOwnedHabit(id, requireUser(authentication));

        habit.setName(request.name());
        habit.setDescription(request.description());
        habit.setFrequency(request.frequency());
        if (request.goalTargetCount() != null) {
            habit.setGoalTargetCount(request.goalTargetCount());
        }
        if (request.goalPeriod() != null) {
            habit.setGoalPeriod(request.goalPeriod());
        }
        applyGoalDefaults(habit);

        return toResponse(habitRepository.save(habit));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable Long id, Authentication authentication) {
        Habit habit = requireOwnedHabit(id, requireUser(authentication));

        habitEntryRepository.deleteByHabitId(id);
        habitRepository.delete(habit);
    }

    @PostMapping("/{id}/complete")
    public HabitResponse completeHabit(@PathVariable Long id, Authentication authentication) {
        Habit habit = requireOwnedHabit(id, requireUser(authentication));

        long start = System.nanoTime();
        Habit updated;

        try {
            if (queueEnabled) {
                // Records the completion and its outbox event in one transaction. The outbox
                // relay publishes the event; the worker computes the reward.
                updated = habitProgressService.recordCompletionOnly(habit);
            } else {
                updated = habitProgressService.completeToday(habit);
            }
        } catch (DataIntegrityViolationException e) {
            // Two concurrent requests can both pass the "already completed?" check before
            // either writes its row; the loser hits the (habit_id, completed_date) unique
            // constraint. That's a lost race, not a real error - the winner's write already
            // recorded the completion, so just return the current state.
            updated = habitRepository.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Habit not found"));

            // Any other constraint failure rolled this completion back. Never report that as success.
            if (!habitProgressService.alreadyCompletedForPeriod(updated, LocalDate.now())) {
                throw e;
            }
        }

        HabitResponse response = toResponse(updated);

        double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
        log.info("COMPLETION_LATENCY mode={} habitId={} elapsedMs={}",
                queueEnabled ? "async" : "sync", id, elapsedMs);

        return response;
    }

    private User requireUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof User user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return user;
    }

    /** Loads a habit and refuses unless it belongs to the caller. Every per-habit endpoint goes through here. */
    private Habit requireOwnedHabit(Long id, User user) {
        Habit habit = habitRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Habit not found"));

        if (habit.getUser() == null || !habit.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This habit belongs to another user");
        }
        return habit;
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

    private HabitResponse toResponse(Habit habit) {
        applyGoalDefaults(habit);

        LocalDate today = LocalDate.now();
        int progressCount;
        if (habit.getGoalPeriod() == Habit.GoalPeriod.WEEKLY) {
            LocalDate start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate end = start.plusDays(6);
            progressCount = (int) habitEntryRepository.countByHabitAndCompletedDateBetween(habit, start, end);
        } else {
            progressCount = habitEntryRepository.findByHabitAndCompletedDate(habit, today).isPresent() ? 1 : 0;
        }

        return HabitResponse.from(habit, progressCount);
    }
}
