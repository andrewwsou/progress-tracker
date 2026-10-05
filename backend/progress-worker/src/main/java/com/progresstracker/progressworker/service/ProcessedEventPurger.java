package com.progresstracker.progressworker.service;

import com.progresstracker.progressworker.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Deletes old rows from {@code processed_events}, so the inbox does not grow without bound.
 *
 * A row is only needed while the queue can still deliver its message. The retention is longer
 * than the queue keeps a message (14 days), so every redelivery still finds its row. One that
 * somehow came later would still stop at the "already has XP" check in {@link CompletionProcessor},
 * before any XP or email.
 */
@Component
public class ProcessedEventPurger {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventPurger.class);

    /** Rows deleted per transaction, so no single delete holds its locks for long. */
    static final int BATCH_SIZE = 10_000;

    /** Upper bound on one run, so a large backlog cannot hold the scheduler thread for long. */
    private static final int MAX_BATCHES_PER_RUN = 100;

    private final ProcessedEventRepository processedEventRepository;
    private final TransactionTemplate transactionTemplate;

    @Value("${processed-events.retention-days:15}")
    private int retentionDays;

    public ProcessedEventPurger(ProcessedEventRepository processedEventRepository,
                                PlatformTransactionManager transactionManager) {
        this.processedEventRepository = processedEventRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${processed-events.purge.fixed-delay-ms:3600000}",
            initialDelayString = "${processed-events.purge.initial-delay-ms:60000}")
    public void purgeOnSchedule() {
        try {
            int deleted = purgeOlderThan(Duration.ofDays(retentionDays), BATCH_SIZE);
            if (deleted > 0) {
                log.info("Purged {} processed events older than {} days", deleted, retentionDays);
            }
        } catch (RuntimeException e) {
            log.error("Processed-events purge failed; will retry on the next run", e);
        }
    }

    /**
     * Deletes the events handled longer ago than {@code age}, one batch per transaction, until a
     * batch comes back short. Whatever one run leaves is picked up by the next.
     *
     * @return how many rows were deleted
     */
    public int purgeOlderThan(Duration age, int batchSize) {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(age);
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN && !Thread.currentThread().isInterrupted(); batch++) {
            Integer deleted = transactionTemplate.execute(status ->
                    processedEventRepository.deleteProcessedBefore(cutoff, batchSize));
            int count = deleted == null ? 0 : deleted;
            total += count;
            if (count < batchSize) {
                break;
            }
        }
        return total;
    }
}
