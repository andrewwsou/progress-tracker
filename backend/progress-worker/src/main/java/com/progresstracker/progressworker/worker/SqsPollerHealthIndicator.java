package com.progresstracker.progressworker.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * The {@code sqsPoller} entry in /actuator/health. DOWN when the poll loop has died or has not
 * asked SQS for messages recently, which a restart would fix; it is part of the liveness group.
 * An unreachable queue does not make it DOWN: the loop keeps retrying, and a restart would not help.
 * Reads in-memory state only, so a health check costs nothing.
 */
@Component
public class SqsPollerHealthIndicator implements HealthIndicator {

    private final SqsPoller poller;
    private final long staleAfterMillis;

    public SqsPollerHealthIndicator(SqsPoller poller,
                                    @Value("${worker.pollStaleAfterSeconds:120}") long staleAfterSeconds) {
        this.poller = poller;
        this.staleAfterMillis = staleAfterSeconds * 1000;
    }

    @Override
    public Health health() {
        if (!poller.isRunning()) {
            // Disabled by configuration, or shutting down.
            return Health.up().withDetail("polling", "off").build();
        }
        long sinceLastPoll = poller.millisSinceLastPoll();
        Health.Builder health = poller.loopAlive() && sinceLastPoll < staleAfterMillis ? Health.up() : Health.down();
        return health
                .withDetail("polling", poller.loopAlive() ? "on" : "stopped unexpectedly")
                .withDetail("inFlight", poller.inFlight())
                .withDetail("concurrency", poller.concurrency())
                .withDetail("msSinceLastPoll", sinceLastPoll)
                .build();
    }
}
