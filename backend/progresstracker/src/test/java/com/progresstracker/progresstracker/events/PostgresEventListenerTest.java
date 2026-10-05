package com.progresstracker.progresstracker.events;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class PostgresEventListenerTest {

    private final UserEventStreams streams = RecordingEmitter.streams(5);

    @AfterEach
    void shutdown() {
        streams.shutdownHeartbeat();
    }

    private PostgresEventListener listener() {
        return new PostgresEventListener(new DataSourceProperties(), streams, new JsonMapper(), false);
    }

    @Test
    void credentialsThatAreNotSetAreLeftOut() {
        // Trust or peer authentication: no user name or password configured.
        DataSourceProperties dataSource = new DataSourceProperties();
        dataSource.setUrl("jdbc:postgresql://localhost:5432/progresstracker");

        Properties properties = new PostgresEventListener(dataSource, streams, new JsonMapper(), true).connectionProperties();

        assertThat(properties).doesNotContainKeys("user", "password");
        assertThat(properties.getProperty("ApplicationName")).isEqualTo("progresstracker-event-listener");
    }

    @Test
    void anEventIsSentOnToThatUsersStreams() {
        streams.start();
        RecordingEmitter mine = (RecordingEmitter) streams.subscribe(1);
        RecordingEmitter someoneElses = (RecordingEmitter) streams.subscribe(2);

        listener().forward("{\"type\":\"reward\",\"userId\":1,\"habitId\":7}");

        assertThat(mine.calls).containsExactly("ready", "reward");
        assertThat(someoneElses.calls).containsExactly("ready");
    }

    @Test
    void aSignOutClosesThatUsersStreamsAndIsNotSentToThem() {
        streams.start();
        RecordingEmitter mine = (RecordingEmitter) streams.subscribe(1);
        RecordingEmitter someoneElses = (RecordingEmitter) streams.subscribe(2);

        // What the instance where user 1 signed out sends to every instance.
        listener().forward("{\"type\":\"signedOut\",\"userId\":1}");

        assertThat(mine.calls).containsExactly("ready", "complete");
        assertThat(someoneElses.calls).containsExactly("ready");
    }

    @Test
    void aNotificationWithoutATypeOrUserIsIgnored() {
        streams.start();
        RecordingEmitter mine = (RecordingEmitter) streams.subscribe(1);

        listener().forward("{\"userId\":1}");
        listener().forward("{\"type\":\"signedOut\"}");
        listener().forward("not json");

        assertThat(mine.calls).containsExactly("ready");
    }

    @Test
    void anUnexpectedFailureIsRetriedInsteadOfEndingTheListener() {
        AtomicInteger attempts = new AtomicInteger();
        DataSourceProperties failing = new DataSourceProperties() {
            @Override
            public String determineUrl() {
                attempts.incrementAndGet();
                throw new IllegalStateException("not an SQLException");
            }
        };
        PostgresEventListener listener = new PostgresEventListener(failing, streams, new JsonMapper(), true);

        listener.start();
        try {
            // A second attempt after the first backoff (1 s) shows the thread survived the first.
            await().atMost(Duration.ofSeconds(10)).until(() -> attempts.get() >= 2);
        } finally {
            listener.stop();
        }
    }
}
