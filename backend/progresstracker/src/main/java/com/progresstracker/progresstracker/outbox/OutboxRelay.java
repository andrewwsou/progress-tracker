package com.progresstracker.progresstracker.outbox;

import com.progresstracker.progresstracker.service.CompletionQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Publishes outbox events to the queue. Runs on a timer, so an event that could not be sent
 * (queue down, API restarted mid-send) is simply picked up again on a later run.
 *
 * What this guarantees is at-least-once, unordered delivery: an event can be sent twice (a crash
 * after the send but before the row is marked), and a retried event can arrive after a newer one.
 * The worker is what makes repeats and reordering harmless.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** SQS accepts at most 10 messages per batch call. */
    private static final int BATCH_SIZE = 10;

    /** Upper bound on one run, so a large backlog cannot starve the scheduler thread. */
    private static final int MAX_BATCHES_PER_RUN = 50;

    private final OutboxEventRepository outboxEventRepository;
    private final CompletionQueueService completionQueueService;
    private final TransactionTemplate transactionTemplate;

    // Read the same way the controller reads it, so the two can never disagree about whether
    // the app is in async mode (an event written must always have a relay to publish it).
    @Value("${queue.enabled:false}")
    private boolean enabled;

    @Value("${outbox.retention-days:7}")
    private int retentionDays;

    public OutboxRelay(OutboxEventRepository outboxEventRepository,
                       CompletionQueueService completionQueueService,
                       PlatformTransactionManager transactionManager) {
        this.outboxEventRepository = outboxEventRepository;
        this.completionQueueService = completionQueueService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay-ms:500}")
    public void publishPending() {
        if (!enabled) {
            return;
        }
        try {
            int batches = 0;
            while (publishNextBatch() == BATCH_SIZE && ++batches < MAX_BATCHES_PER_RUN) {
                // keep draining while batches come back full
            }
        } catch (RuntimeException e) {
            // Nothing is lost: unpublished rows stay in the table and the next run retries them.
            log.error("Outbox relay run failed; will retry on the next run", e);
        }
    }

    /**
     * Claims up to one batch, sends it, and marks what the queue accepted as published.
     * All in one transaction: if anything fails the rows stay unpublished and are retried.
     *
     * @return how many events the queue accepted
     */
    public int publishNextBatch() {
        Integer claimed = transactionTemplate.execute(status -> {
            List<OutboxEvent> batch = outboxEventRepository.lockNextUnpublished(BATCH_SIZE);
            if (batch.isEmpty()) {
                return 0;
            }

            Set<UUID> accepted = completionQueueService.publish(batch);

            OffsetDateTime now = OffsetDateTime.now();
            batch.stream()
                    .filter(event -> accepted.contains(event.getId()))
                    .forEach(event -> event.markPublished(now));

            if (accepted.size() < batch.size()) {
                log.warn("Queue accepted {} of {} outbox events; the rest will be retried",
                        accepted.size(), batch.size());
            }
            // Counting what was accepted, not what was claimed, means a batch the queue keeps
            // rejecting ends this run instead of being re-sent in a tight loop.
            return accepted.size();
        });
        return claimed == null ? 0 : claimed;
    }

    /** Published rows are only kept for a while, so the table does not grow without bound. */
    @Scheduled(fixedDelayString = "${outbox.purge.fixed-delay-ms:3600000}",
            initialDelayString = "${outbox.purge.initial-delay-ms:60000}")
    public void purgePublished() {
        if (!enabled) {
            return;
        }
        try {
            int deleted = purgePublishedOlderThan(Duration.ofDays(retentionDays));
            if (deleted > 0) {
                log.info("Purged {} published outbox events", deleted);
            }
        } catch (RuntimeException e) {
            log.error("Outbox purge failed; will retry on the next run", e);
        }
    }

    public int purgePublishedOlderThan(Duration age) {
        Integer deleted = transactionTemplate.execute(status ->
                outboxEventRepository.deletePublishedBefore(OffsetDateTime.now().minus(age)));
        return deleted == null ? 0 : deleted;
    }
}
