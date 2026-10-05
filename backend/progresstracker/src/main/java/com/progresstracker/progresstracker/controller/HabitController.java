package com.progresstracker.progresstracker.controller;

import com.progresstracker.progresstracker.dto.HabitRequest;
import com.progresstracker.progresstracker.dto.HabitResponse;
import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.model.User;
import com.progresstracker.progresstracker.repository.HabitEntryRepository;
import com.progresstracker.progresstracker.repository.HabitEntryRepository.PeriodProgress;
import com.progresstracker.progresstracker.repository.HabitRepository;
import com.progresstracker.progresstracker.repository.UserRepository;
import com.progresstracker.progresstracker.service.HabitProgressService;
import com.progresstracker.progresstracker.service.UserCalendar;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/habits")
@Tag(name = "Habits")
public class HabitController {

    /** Enough for any real routine, and it keeps one account from making the habit list arbitrarily expensive. */
    static final int MAX_HABITS_PER_USER = 100;

    private static final Logger log = LoggerFactory.getLogger(HabitController.class);

    private final HabitRepository habitRepository;
    private final UserRepository userRepository;
    private final HabitProgressService habitProgressService;
    private final HabitEntryRepository habitEntryRepository;
    private final UserCalendar calendar;

    @Value("${queue.enabled:false}")
    private boolean queueEnabled;

    public HabitController(HabitRepository habitRepository,
                           UserRepository userRepository,
                           HabitProgressService habitProgressService,
                           HabitEntryRepository habitEntryRepository,
                           UserCalendar calendar) {
        this.habitRepository = habitRepository;
        this.userRepository = userRepository;
        this.habitProgressService = habitProgressService;
        this.habitEntryRepository = habitEntryRepository;
        this.calendar = calendar;
    }

    /** Oldest first. The dashboard reloads this after every action, so it costs the same few queries however many habits there are. */
    @GetMapping
    public List<HabitResponse> getAllHabits(Authentication authentication) {
        User user = requireUser(authentication);
        return toResponses(habitRepository.findByUserOrderByIdAsc(user), calendar.today(user));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "409", description = "The caller already has the most habits allowed",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    public HabitResponse createHabit(@Valid @RequestBody HabitRequest request, Authentication authentication) {
        User user = requireUser(authentication);
        // Two creates at once can both pass this and end one over the limit. That is harmless:
        // the limit is there to stop runaway growth, not to be exact.
        if (habitRepository.countByUser(user) >= MAX_HABITS_PER_USER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You can have at most " + MAX_HABITS_PER_USER + " habits.");
        }

        // Built field by field from the request. The id, the owner, and the reward state never
        // come from the client, so a request cannot overwrite or take over an existing habit.
        Habit habit = new Habit(user, request.name(), request.description(), request.frequency());
        habit.setGoalTargetCount(request.goalTargetCount());
        habit.setGoalPeriod(request.goalPeriod());
        applyGoalDefaults(habit);
        requireReachableGoal(habit);

        return toResponse(habitRepository.save(habit));
    }

    @PutMapping("/{id}")
    @Transactional
    public HabitResponse update(@PathVariable Long id,
                                @Valid @RequestBody HabitRequest request,
                                Authentication authentication) {
        Habit habit = requireOwnedHabit(id, requireUser(authentication));

        // A goal left out of the request is kept, unless the frequency changes: then it no longer
        // fits, so it goes back to the new frequency's default.
        boolean frequencyChanged = habit.getFrequency() != request.frequency();
        habit.setName(request.name());
        habit.setDescription(request.description());
        habit.setFrequency(request.frequency());
        if (request.goalTargetCount() != null) {
            habit.setGoalTargetCount(request.goalTargetCount());
        } else if (frequencyChanged) {
            habit.setGoalTargetCount(null);
        }
        if (request.goalPeriod() != null) {
            habit.setGoalPeriod(request.goalPeriod());
        } else if (frequencyChanged) {
            habit.setGoalPeriod(null);
        }
        applyGoalDefaults(habit);
        requireReachableGoal(habit);

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
            // recorded the completion, so just return the current state. (In sync mode the
            // user's row lock makes them take turns instead; async mode still races.)
            updated = habitRepository.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Habit not found"));

            // Any other constraint failure rolled this completion back. Never report that as success.
            if (!habitProgressService.alreadyCompletedForPeriod(updated, calendar.today(updated.getUser()))) {
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

    private static void applyGoalDefaults(Habit habit) {
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

    /**
     * A habit counts once per day (daily) or once per week (weekly), so only these goals can be
     * met: once a day or 1 to 7 days a week for a daily habit, and once a week for a weekly one.
     * Checked on writes only. Habits saved before this rule still load.
     */
    private static void requireReachableGoal(Habit habit) {
        boolean weeklyHabit = habit.getFrequency() == Habit.Frequency.WEEKLY;
        int target = habit.getGoalTargetCount();
        boolean reachable = habit.getGoalPeriod() == Habit.GoalPeriod.DAILY
                ? !weeklyHabit && target == 1
                : target <= (weeklyHabit ? 1 : 7);
        if (!reachable) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This goal can never be met. A daily habit can aim for once a day or 1 to 7 days a week; "
                            + "a weekly habit, once a week.");
        }
    }

    private HabitResponse toResponse(Habit habit) {
        return toResponses(List.of(habit), calendar.today(habit.getUser())).get(0);
    }

    /**
     * The responses for habits that share one owner, so one "today". Progress for all of them
     * comes from one query.
     */
    private List<HabitResponse> toResponses(List<Habit> habits, LocalDate today) {
        if (habits.isEmpty()) {
            return List.of();
        }
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Map<Long, PeriodProgress> progressByHabit = habitEntryRepository
                .findProgress(habits.stream().map(Habit::getId).toList(), today, weekStart, weekStart.plusDays(6))
                .stream()
                .collect(Collectors.toMap(PeriodProgress::getHabitId, Function.identity()));

        return habits.stream()
                .map(habit -> toResponse(habit, today, progressByHabit.get(habit.getId())))
                .toList();
    }

    /** @param progress the habit's completions this week, or null if it has none */
    private static HabitResponse toResponse(Habit habit, LocalDate today, PeriodProgress progress) {
        applyGoalDefaults(habit);
        if (progress == null) {
            return HabitResponse.from(habit, 0, false);
        }

        long progressCount = habit.getGoalPeriod() == Habit.GoalPeriod.WEEKLY ? progress.getThisWeek() : progress.getOnToday();
        // The same rule as HabitProgressService.alreadyCompletedForPeriod, applied to the latest completion.
        boolean completedForPeriod = !progress.getLatest().isBefore(HabitProgressService.periodStart(habit, today));
        return HabitResponse.from(habit, (int) progressCount, completedForPeriod);
    }
}
