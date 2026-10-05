package com.progresstracker.progresstracker.dto;

import com.progresstracker.progresstracker.model.Habit;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The only fields a client may set when creating or editing a habit. The id, the owner, and
 * all reward state (XP, streaks) are deliberately absent: the server owns those.
 */
public record HabitRequest(
        @NotBlank @Size(min = 1, max = 100) @Pattern(regexp = SINGLE_LINE, message = SINGLE_LINE_MESSAGE) String name,
        @Size(max = 255) @Pattern(regexp = SINGLE_LINE, message = SINGLE_LINE_MESSAGE) String description,
        @NotNull Habit.Frequency frequency,
        // At most 7: the most a goal can ask for is every day of a week (see HabitController).
        @Min(1) @Max(7) Integer goalTargetCount,
        Habit.GoalPeriod goalPeriod
) {

    // No control characters (U+0000 to U+001F, U+007F to U+009F) or line breaks: both are one-line
    // text, and a name ends up in log lines and in the prompt for weekly summaries, where a line
    // break could pass for something else. Written with plain escapes, and anchored because a JSON
    // Schema pattern only has to match somewhere in the value, so clients can reuse the contract's
    // pattern as it is.
    private static final String SINGLE_LINE = "^[^\\x00-\\x1F\\x7F-\\x9F\\u2028\\u2029]*$";
    private static final String SINGLE_LINE_MESSAGE = "must not contain control characters or line breaks";
}
