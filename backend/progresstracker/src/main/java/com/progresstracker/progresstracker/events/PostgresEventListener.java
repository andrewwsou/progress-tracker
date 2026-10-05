package com.progresstracker.progresstracker.events;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listens on the PostgreSQL channel {@value #CHANNEL} and forwards each notification to the
 * user's open event streams. The worker sends a notification inside the transaction that applies
 * a reward (or finishes a weekly summary), and PostgreSQL only delivers it once that transaction
 * commits, so a browser never hears about a change that was rolled back.
 *
 * Uses its own connection, outside the pool: it is held open for as long as the API runs.
 * Every API instance listens, and each forwards only to the streams it holds.
 *
 * Only reading, the connection would never notice a peer that vanished without closing it (a
 * crashed host, a partition), so it is checked every {@value #CHECK_MILLIS} ms and reopened
 * when dead. After every (re)connect all open streams are told to re-read their state, since
 * notifications sent while nobody was listening are gone.
 */
@Component
public class PostgresEventListener implements SmartLifecycle {

    static final String CHANNEL = "habit_events";

    private static final Logger log = LoggerFactory.getLogger(PostgresEventListener.class);
    private static final int POLL_MILLIS = 5_000;
    private static final long CHECK_MILLIS = 30_000;
    private static final long MAX_BACKOFF_MILLIS = 10_000;

    private final DataSourceProperties dataSource;
    private final UserEventStreams streams;
    private final JsonMapper jsonMapper;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;
    private volatile Connection connection;

    public PostgresEventListener(DataSourceProperties dataSource,
                                 UserEventStreams streams,
                                 JsonMapper jsonMapper,
                                 @Value("${events.listener.enabled:true}") boolean enabled) {
        this.dataSource = dataSource;
        this.streams = streams;
        this.jsonMapper = jsonMapper;
        this.enabled = enabled;
    }

    @Override
    public void start() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::listenLoop, "postgres-event-listener");
        thread.setDaemon(true);
        thread.start();
    }

    private void listenLoop() {
        long backoff = 0;
        while (running.get()) {
            try (Connection conn = DriverManager.getConnection(dataSource.determineUrl(), connectionProperties())) {
                connection = conn;
                try (Statement statement = conn.createStatement()) {
                    statement.execute("LISTEN " + CHANNEL);
                }
                log.info("Listening for {} notifications", CHANNEL);
                backoff = 0;
                streams.publishAll("resync");
                PGConnection pg = conn.unwrap(PGConnection.class);
                long nextCheck = System.currentTimeMillis() + CHECK_MILLIS;
                while (running.get()) {
                    PGNotification[] notifications = pg.getNotifications(POLL_MILLIS);
                    if (notifications != null) {
                        for (PGNotification notification : notifications) {
                            forward(notification.getParameter());
                        }
                    }
                    if (System.currentTimeMillis() >= nextCheck) {
                        // A round trip; notifications read along the way are kept for the next poll.
                        if (!conn.isValid(5)) {
                            throw new SQLException("Listener connection is no longer answering");
                        }
                        nextCheck = System.currentTimeMillis() + CHECK_MILLIS;
                    }
                }
            } catch (SQLException e) {
                if (!running.get()) {
                    return;
                }
                backoff = backoff == 0 ? 1_000 : Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
                log.warn("Event listener lost its connection; reconnecting in {} ms", backoff, e);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private Properties connectionProperties() {
        Properties properties = new Properties();
        properties.setProperty("user", dataSource.determineUsername());
        properties.setProperty("password", dataSource.determinePassword());
        properties.setProperty("tcpKeepAlive", "true");
        properties.setProperty("ApplicationName", "progresstracker-event-listener");
        return properties;
    }

    /** Payload: {"type": "reward" | "summary", "userId": 1, ...}. Anything else is ignored. */
    private void forward(String payload) {
        try {
            JsonNode event = jsonMapper.readTree(payload);
            if (event.hasNonNull("type") && event.hasNonNull("userId")) {
                streams.publish(event.get("userId").asLong(), event.get("type").asString(), payload);
            }
        } catch (Exception e) {
            log.warn("Ignoring unreadable {} notification: {}", CHANNEL, payload, e);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false) && thread != null) {
            Connection current = connection;
            try {
                if (current != null) {
                    current.abort(Runnable::run); // ends a blocked read at once
                }
            } catch (SQLException e) {
                log.debug("Could not abort the listener connection", e);
            }
            thread.interrupt(); // ends a reconnect backoff
            try {
                thread.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
