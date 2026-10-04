package com.progresstracker.progresstracker.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.progresstracker.progresstracker.model.Habit;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * What the API returns for a habit. Keeps the JPA entity, and its owner, out of the response.
 * Fields with no value are left out rather than sent as null, matching the contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HabitResponse(
        @Schema(requiredMode = REQUIRED) long id,
        @Schema(requiredMode = REQUIRED) String name,
        String description,
        @Schema(requiredMode = REQUIRED) Habit.Frequency frequency,
        @Schema(requiredMode = REQUIRED) int goalTargetCount,
        @Schema(requiredMode = REQUIRED) Habit.GoalPeriod goalPeriod,
        @Schema(requiredMode = REQUIRED, description = "Completions so far in the current goal period") int progressCount,
        @Schema(requiredMode = REQUIRED) int progressTargetCount,
        @Schema(requiredMode = REQUIRED) int xpTotal,
        @Schema(requiredMode = REQUIRED) int currentStreak,
        @Schema(requiredMode = REQUIRED) int longestStreak,
        LocalDate lastCompletedDate,
        OffsetDateTime createdAt
) {

    public static HabitResponse from(Habit habit, int progressCount) {
        return new HabitResponse(
                habit.getId(),
                habit.getName(),
                habit.getDescription(),
                habit.getFrequency(),
                habit.getGoalTargetCount(),
                habit.getGoalPeriod(),
                progressCount,
                habit.getGoalTargetCount(),
                habit.getXpTotal(),
                habit.getCurrentStreak(),
                habit.getLongestStreak(),
                habit.getLastCompletedDate(),
                habit.getCreatedAt() == null
                        ? null
                        : habit.getCreatedAt().atZone(ZoneId.systemDefault()).toOffsetDateTime());
    }
}
