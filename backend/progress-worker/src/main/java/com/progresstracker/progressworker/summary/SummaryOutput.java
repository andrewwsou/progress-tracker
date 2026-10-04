package com.progresstracker.progressworker.summary;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * The text of a weekly summary. The Claude SDK derives the JSON schema for structured output from
 * this record, so the model's answer always has exactly these fields.
 */
@JsonClassDescription("A short weekly summary of one user's habit activity")
public record SummaryOutput(
        @JsonPropertyDescription("One sentence of at most 80 characters")
        String headline,
        @JsonPropertyDescription("Two or three sentences, at most 400 characters in total")
        String body,
        @JsonPropertyDescription("The exact name of one habit from the data to focus on next week")
        String focusHabit
) {
}
