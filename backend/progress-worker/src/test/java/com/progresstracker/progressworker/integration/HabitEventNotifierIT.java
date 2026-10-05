package com.progresstracker.progressworker.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The worker's notifications to the API ride on PostgreSQL LISTEN/NOTIFY. What matters is that
 * one is sent once a reward has committed, and never for a reward that failed.
 */
class HabitEventNotifierIT extends WorkerIntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    private Connection listener;
    private final List<String> received = new ArrayList<>();

    @BeforeEach
    void listen() throws SQLException {
        listener = dataSource.getConnection();
        try (Statement statement = listener.createStatement()) {
            statement.execute("LISTEN habit_events");
        }
    }

    @AfterEach
    void stopListening() throws SQLException {
        listener.close();
    }

    private List<String> poll() throws SQLException {
        PGNotification[] notifications = listener.unwrap(PGConnection.class).getNotifications(200);
        if (notifications != null) {
            for (PGNotification n : notifications) {
                received.add(n.getParameter());
            }
        }
        return received;
    }

    @Test
    void aRewardAppliedFromTheQueueNotifiesTheApi() {
        long userId = insertUser();
        long habitId = insertHabit(userId);
        insertEntry(habitId, LocalDate.now(), 0);

        LocalSqs.send(QUEUE_URL, "{\"eventId\":\"" + UUID.randomUUID() + "\",\"userId\":" + userId + ",\"habitId\":"
                + habitId + ",\"date\":\"" + LocalDate.now() + "\",\"occurredAt\":\"" + OffsetDateTime.now() + "\"}");

        await().atMost(TIMEOUT).until(() -> poll().contains(
                "{\"type\":\"reward\",\"userId\":" + userId + ",\"habitId\":" + habitId + "}"));
        // Sent once, after the reward committed: the habit already has its XP.
        assertThat(xpTotal(habitId)).isEqualTo(10);
    }

    @Test
    void aRewardThatFailsIsNeverAnnounced() {
        long owner = insertUser();
        long someoneElse = insertUser();
        long habitId = insertHabit(owner);
        long goodUser = insertUser();
        long goodHabit = insertHabit(goodUser);
        insertEntry(goodHabit, LocalDate.now(), 0);

        // Fails every time (the user does not own the habit), so its transaction always rolls back.
        LocalSqs.send(QUEUE_URL, event(someoneElse, habitId));
        LocalSqs.send(QUEUE_URL, event(goodUser, goodHabit));

        await().atMost(TIMEOUT).until(() -> poll().stream().anyMatch(p -> p.contains("\"habitId\":" + goodHabit + "}")));
        assertThat(received).noneMatch(p -> p.contains("\"habitId\":" + habitId + "}"));
    }

    private static String event(long userId, long habitId) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"userId\":" + userId + ",\"habitId\":" + habitId
                + ",\"date\":\"" + LocalDate.now() + "\",\"occurredAt\":\"" + OffsetDateTime.now() + "\"}";
    }
}
