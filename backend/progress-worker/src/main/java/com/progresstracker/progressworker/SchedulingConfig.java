package com.progresstracker.progressworker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the worker's timers: the weekly summary job and the processed-events purge. Set
 * {@code worker.scheduling-enabled=false} to start without them, for example in a test that runs
 * them by hand.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "worker.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
