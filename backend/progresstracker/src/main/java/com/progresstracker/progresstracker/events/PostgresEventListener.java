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
 * user's open event streams. The worker sends a notification just after a reward (or a weekly
 * summary) has committed, outside that transaction (see the worker's HabitEventNotifier), so a
 * browser never hears about a change that did not happen. A worker crash between the commit and
 * the notification loses that one notification. It is only a hint: browsers re-read their state
 * after their own actions, on reconnect, and on {@code resync}.
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

    public static final String CHANNEL = "habit_events";

    /** The notification an API instance sends when a user signs out everywhere. */
    public static final String SIGNED_OUT = "signedOut";

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
            } catch (SQLException | RuntimeException e) {
                // Anything unexpected is retried the same way. Left to escape, it would end this
                // thread, and streams would go on saying they are live with nobody listening.
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

    Properties connectionProperties() {
        Properties properties = new Properties();
        // Either can be null (trust or peer authentication), and Properties refuses null values.
        String user = dataSource.determineUsername();
        String password = dataSource.determinePassword();
        if (user != null) {
            properties.setProperty("user", user);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        properties.setProperty("tcpKeepAlive", "true");
        properties.setProperty("ApplicationName", "progresstracker-event-listener");
        return properties;
    }

    /**
     * Payload: {"type": "reward" | "summary", "userId": 1, ...}, sent on to the user's streams.
     * {@value #SIGNED_OUT} is internal and never reaches a browser: the API instance where the
     * user signed out everywhere sends it, and every instance closes that user's streams.
     * Anything else is ignored.
     */
    void forward(String payload) {
        try {
            JsonNode event = jsonMapper.readTree(payload);
            if (event.hasNonNull("type") && event.hasNonNull("userId")) {
                long userId = event.get("userId").asLong();
                String type = event.get("type").asString();
                if (SIGNED_OUT.equals(type)) {
                    streams.closeAll(userId);
                } else {
                    streams.publish(userId, type, payload);
                }
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
