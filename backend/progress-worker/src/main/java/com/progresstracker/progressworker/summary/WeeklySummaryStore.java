package com.progresstracker.progressworker.summary;

import com.progresstracker.progressworker.model.WeeklySummary;
import com.progresstracker.progressworker.repository.WeeklySummaryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The weekly_summaries table used as a job queue. Each method is one short transaction, so no
 * database lock or connection is held while the model is being called.
 */
@Component
public class WeeklySummaryStore {

    /**
     * A summary this worker has claimed. {@code attempt} is the fencing token: only the latest
     * claim on a row may finish it.
     */
    public record Claim(long id, long userId, LocalDate weekStart, int attempt) {
    }

    private final WeeklySummaryRepository repository;

    public WeeklySummaryStore(WeeklySummaryRepository repository) {
        this.repository = repository;
    }

    /**
     * Claims up to {@code limit} waiting summaries for {@code lease}. A summary whose previous
     * claim expired (its worker crashed or stalled) can be claimed again, up to {@code maxAttempts}
     * claims in total; after that it is marked FAILED.
     */
    @Transactional
    public List<Claim> claim(int limit, Duration lease, int maxAttempts) {
        OffsetDateTime now = OffsetDateTime.now();
        repository.failExhausted(WeeklySummary.Status.FAILED, WeeklySummary.Status.PENDING,
                WeeklySummary.Status.IN_PROGRESS, now, maxAttempts, "Gave up after " + maxAttempts + " attempts");

        OffsetDateTime leaseUntil = now.plus(lease);
        return repository.lockClaimable(now, maxAttempts, limit).stream()
                .map(row -> {
                    row.claim(leaseUntil);
                    return new Claim(row.getId(), row.getUser().getId(), row.getWeekStart(), row.getAttempts());
                })
                .toList();
    }

    /**
     * Restarts a claim's lease just before its summary is written. A batch is written one summary
     * at a time, so without this the last ones could outlive a lease taken for the whole batch and
     * be claimed, and paid for, a second time.
     *
     * @return false if another worker has claimed the row since, so it must not be written
     */
    @Transactional
    public boolean renew(Claim claim, Duration lease) {
        return repository.renewLease(claim.id(), claim.attempt(), WeeklySummary.Status.IN_PROGRESS,
                OffsetDateTime.now().plus(lease)) == 1;
    }

    /**
     * Saves the finished summary, unless another worker has claimed the row since (its lease
     * expired). The row lock makes the check and the write one atomic step.
     *
     * @return false if this claim was stale and nothing was written
     */
    @Transactional
    public boolean finish(Claim claim, WeeklySummary.Result result) {
        WeeklySummary row = currentRow(claim);
        if (row == null) {
            return false;
        }
        row.markReady(result, OffsetDateTime.now());
        return true;
    }

    /** Hands a claimed summary back after a failed attempt: to be retried, or FAILED after the last one. */
    @Transactional
    public void release(Claim claim, int maxAttempts, String error) {
        WeeklySummary row = currentRow(claim);
        if (row != null) {
            row.release(row.getAttempts() >= maxAttempts, truncate(error, 300));
        }
    }

    /** The row, locked, if this claim is still the current one. */
    private WeeklySummary currentRow(Claim claim) {
        return repository.findByIdForUpdate(claim.id())
                .filter(row -> row.getStatus() == WeeklySummary.Status.IN_PROGRESS)
                .filter(row -> row.getAttempts() == claim.attempt())
                .orElse(null);
    }

    static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }
}
