package com.progresstracker.progresstracker.events;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserEventStreamsTest {

    private final UserEventStreams streams = RecordingEmitter.streams(2);

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
    void theEvictedStreamIsToldWhyBeforeItCloses() {
        streams.start();

        RecordingEmitter oldest = (RecordingEmitter) streams.subscribe(1);
        RecordingEmitter second = (RecordingEmitter) streams.subscribe(1);
        RecordingEmitter third = (RecordingEmitter) streams.subscribe(1);

        // Without the reason a client reconnects at once and evicts the next oldest in turn.
        assertThat(oldest.calls).containsExactly("ready", "evicted", "complete");
        assertThat(second.calls).containsExactly("ready");
        assertThat(third.calls).containsExactly("ready");
    }

    @Test
    void signingOutClosesEveryStreamOfThatUserAndNoOneElses() {
        streams.start();
        RecordingEmitter firstTab = (RecordingEmitter) streams.subscribe(1);
        RecordingEmitter secondTab = (RecordingEmitter) streams.subscribe(1);
        RecordingEmitter someoneElse = (RecordingEmitter) streams.subscribe(2);

        streams.closeAll(1);
        streams.closeAll(3); // no streams: nothing to do

        assertThat(streams.openStreams(1)).isZero();
        assertThat(streams.openStreams(2)).isEqualTo(1);
        // Closed with no event: a browser that reconnects with its revoked token is refused.
        assertThat(firstTab.calls).containsExactly("ready", "complete");
        assertThat(secondTab.calls).containsExactly("ready", "complete");
        assertThat(someoneElse.calls).containsExactly("ready");
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
