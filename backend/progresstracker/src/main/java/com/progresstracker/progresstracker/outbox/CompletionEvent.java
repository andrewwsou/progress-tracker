package com.progresstracker.progresstracker.outbox;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** The message the worker receives when a habit is completed. This is the queue's contract. */
public record CompletionEvent(
        UUID eventId,
        long userId,
        long habitId,
        LocalDate date,
        OffsetDateTime occurredAt
) {
    public static final String TYPE = "habit.completed";

    /** The JSON fields of the message. Dates are written as ISO-8601 strings. */
    public Map<String, Object> toMessage() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("eventId", eventId.toString());
        message.put("userId", userId);
        message.put("habitId", habitId);
        message.put("date", date.toString());
        message.put("occurredAt", occurredAt.toString());
        return message;
    }
}
