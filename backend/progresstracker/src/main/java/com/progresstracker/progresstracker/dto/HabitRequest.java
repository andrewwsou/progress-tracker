package com.progresstracker.progresstracker.dto;

import com.progresstracker.progresstracker.model.Habit;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The only fields a client may set when creating or editing a habit. The id, the owner, and
 * all reward state (XP, streaks) are deliberately absent: the server owns those.
 */
public record HabitRequest(
        @NotBlank @Size(min = 1, max = 100) String name,
        @Size(max = 255) String description,
        @NotNull Habit.Frequency frequency,
        @Min(1) @Max(365) Integer goalTargetCount,
        Habit.GoalPeriod goalPeriod
) {
}
