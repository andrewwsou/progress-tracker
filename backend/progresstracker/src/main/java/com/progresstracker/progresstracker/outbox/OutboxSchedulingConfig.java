package com.progresstracker.progresstracker.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the timer that drives {@link OutboxRelay}. Tests that want to call the relay by hand,
 * with no timer running underneath them, set {@code outbox.relay.scheduling-enabled=false}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "outbox.relay.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxSchedulingConfig {
}
