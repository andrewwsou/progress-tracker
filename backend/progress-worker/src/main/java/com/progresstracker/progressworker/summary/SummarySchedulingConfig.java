package com.progresstracker.progressworker.summary;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the timer that drives {@link WeeklySummaryJob}. Set
 * {@code summary.job.scheduling-enabled=false} to start without it, for example in a test that
 * runs the job by hand.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "summary.job.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class SummarySchedulingConfig {
}
