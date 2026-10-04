package com.progresstracker.progresstracker.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.progresstracker.progresstracker.model.WeeklySummary;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** A finished weekly summary. The numbers come from the database; only the wording may come from Claude. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WeeklySummaryResponse(
        @Schema(requiredMode = REQUIRED) LocalDate weekStart,
        @Schema(requiredMode = REQUIRED) LocalDate weekEnd,
        @Schema(requiredMode = REQUIRED) int completions,
        @Schema(requiredMode = REQUIRED) int xpEarned,
        @Schema(requiredMode = REQUIRED) String headline,
        @Schema(requiredMode = REQUIRED) String body,
        @Schema(description = "The habit suggested for attention next week") String focusHabit,
        @Schema(requiredMode = REQUIRED, description = "AI when Claude wrote the text, TEMPLATE when the fallback template did")
        WeeklySummary.Source source,
        @Schema(requiredMode = REQUIRED) OffsetDateTime writtenAt
) {

    public static WeeklySummaryResponse from(WeeklySummary summary) {
        return new WeeklySummaryResponse(
                summary.getWeekStart(),
                summary.getWeekStart().plusDays(6),
                summary.getCompletions() == null ? 0 : summary.getCompletions(),
                summary.getXpEarned() == null ? 0 : summary.getXpEarned(),
                summary.getHeadline(),
                summary.getBody(),
                summary.getFocusHabit(),
                summary.getSource(),
                summary.getCompletedAt());
    }
}
