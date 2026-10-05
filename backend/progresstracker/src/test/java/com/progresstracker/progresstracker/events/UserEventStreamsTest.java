package com.progresstracker.progresstracker.events;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserEventStreamsTest {

    private final UserEventStreams streams = new UserEventStreams(Duration.ofMinutes(30), 3600, 2);

    @AfterEach
    void shutdown() {
        streams.shutdownHeartbeat();
    }

    @Test
    void eachUserKeepsAtMostTheConfiguredNumberOfStreams() {
        streams.start();

        streams.subscribe(1);
        streams.subscribe(1);
        streams.subscribe(1); // a third tab: the oldest stream is closed
        streams.subscribe(2);

        assertThat(streams.openStreams(1)).isEqualTo(2);
        assertThat(streams.openStreams(2)).isEqualTo(1);
    }

    @Test
    void noNewStreamsOnceShuttingDown() {
        streams.start();
        streams.subscribe(1);

        streams.stop();

        assertThat(streams.openStreams(1)).isZero();
        assertThatThrownBy(() -> streams.subscribe(1))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Shutting down");
    }
}
