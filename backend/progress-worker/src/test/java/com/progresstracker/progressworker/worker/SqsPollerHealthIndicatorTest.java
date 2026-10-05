package com.progresstracker.progressworker.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqsPollerHealthIndicatorTest {

    private final SqsPoller poller = mock(SqsPoller.class);
    private final SqsPollerHealthIndicator indicator = new SqsPollerHealthIndicator(poller, 90);

    @Test
    void aWorkerThatDoesNotPollIsHealthy() {
        when(poller.isRunning()).thenReturn(false);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("polling", "off");
    }

    @Test
    void aLoopThatPolledRecentlyIsHealthy() {
        when(poller.isRunning()).thenReturn(true);
        when(poller.loopAlive()).thenReturn(true);
        when(poller.millisSinceLastPoll()).thenReturn(1_000L);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aLoopThatHasNotPolledForTooLongIsDown() {
        when(poller.isRunning()).thenReturn(true);
        when(poller.loopAlive()).thenReturn(true);
        when(poller.millisSinceLastPoll()).thenReturn(91_000L);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void aLoopThatDiedIsDown() {
        when(poller.isRunning()).thenReturn(true);
        when(poller.loopAlive()).thenReturn(false);
        when(poller.millisSinceLastPoll()).thenReturn(1_000L);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("polling", "stopped unexpectedly");
    }
}
