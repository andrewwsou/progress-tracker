package com.progresstracker.progressworker.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tells the API that something changed for a user, through PostgreSQL's NOTIFY on the
 * {@code habit_events} channel. Call it after the change has committed, outside its transaction:
 * then listeners never hear about a change that did not happen.
 *
 * Not inside the transaction: a transaction that sends NOTIFY takes a database-wide lock while it
 * commits, so the worker's concurrent reward commits would queue behind each other (measured in
 * load/RESULTS.md). Sent on its own, the notification holds that lock only for an instant. The
 * cost is that a crash between the commit and this call loses one notification, which is only a
 * hint: browsers re-read their state on reconnect and after their own actions.
 */
@Component
public class HabitEventNotifier {

    static final String CHANNEL = "habit_events";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public HabitEventNotifier(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void rewardApplied(long userId, long habitId) {
        Map<String, Object> event = event("reward", userId);
        event.put("habitId", habitId);
        send(event);
    }

    public void summaryReady(long userId) {
        send(event("summary", userId));
    }

    /** Keys in a fixed order, so the payload is the same text every time. */
    private static Map<String, Object> event(String type, long userId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("userId", userId);
        return event;
    }

    private void send(Map<String, Object> event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            // pg_notify takes the channel as a parameter. Outside a transaction this commits at once.
            jdbc.query("select pg_notify(?, ?)", rs -> null, CHANNEL, payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize event " + event, e);
        }
    }
}
