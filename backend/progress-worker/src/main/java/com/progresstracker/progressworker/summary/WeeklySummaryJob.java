package com.progresstracker.progressworker.summary;

import com.progresstracker.progressworker.events.HabitEventNotifier;
import com.progresstracker.progressworker.model.WeeklySummary;
import com.progresstracker.progressworker.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Writes the weekly summaries the API has asked for. Runs on its own timer thread, so a slow
 * model call never delays the queue poller that applies rewards.
 */
@Component
public class WeeklySummaryJob {

    private static final Logger log = LoggerFactory.getLogger(WeeklySummaryJob.class);

    private final WeeklySummaryStore store;
    private final WeeklyStatsReader statsReader;
    private final SummaryGenerator generator;
    private final EmailService emailService;
    private final HabitEventNotifier eventNotifier;
    private final SummaryProperties.Job config;
    private volatile boolean shuttingDown;

    public WeeklySummaryJob(WeeklySummaryStore store,
                            WeeklyStatsReader statsReader,
                            SummaryGenerator generator,
                            EmailService emailService,
                            HabitEventNotifier eventNotifier,
                            SummaryProperties properties) {
        this.store = store;
        this.statsReader = statsReader;
        this.generator = generator;
        this.emailService = emailService;
        this.eventNotifier = eventNotifier;
        this.config = properties.job();
    }

    @Scheduled(fixedDelayString = "${summary.job.fixed-delay-ms:15000}",
            initialDelayString = "${summary.job.initial-delay-ms:5000}")
    public void runOnSchedule() {
        try {
            // A full batch means more may be waiting, so keep going until one comes back short.
            int claimed;
            do {
                claimed = runOnce();
            } while (claimed == config.batchSize() && !shuttingDown && !Thread.currentThread().isInterrupted());
        } catch (RuntimeException e) {
            log.error("Weekly summary run failed; the next run will try again", e);
        }
    }

    /** Stops claiming new batches once the application starts shutting down. */
    @EventListener(ContextClosedEvent.class)
    void onShutdown() {
        shuttingDown = true;
    }

    /** Claims a batch of waiting summaries and writes each one. Returns how many were claimed. */
    public int runOnce() {
        List<WeeklySummaryStore.Claim> claims = store.claim(config.batchSize(), config.lease(), config.maxAttempts());
        claims.forEach(this::process);
        return claims.size();
    }

    private void process(WeeklySummaryStore.Claim claim) {
        try {
            if (!store.renew(claim, config.lease())) {
                log.warn("Weekly summary {} was claimed by another worker while waiting in this batch; skipping it", claim.id());
                return;
            }
            WeekStats stats = statsReader.read(claim.userId(), claim.weekStart());
            GeneratedSummary summary = generator.generate(stats);
            WeeklySummary.Result result = toResult(stats, summary);

            if (!store.finish(claim, result)) {
                log.warn("Weekly summary {} was claimed by another worker in the meantime; discarding this copy", claim.id());
                return;
            }

            AiUsage usage = summary.usage();
            log.info("WEEKLY_SUMMARY_READY summaryId={} userId={} weekStart={} source={} model={} inputTokens={} "
                            + "outputTokens={} latencyMs={} fallbackReason={}",
                    claim.id(), claim.userId(), claim.weekStart(), summary.source(),
                    usage == null ? null : usage.model(),
                    usage == null ? null : usage.inputTokens(),
                    usage == null ? null : usage.outputTokens(),
                    usage == null ? null : usage.latencyMs(),
                    summary.fallbackReason());
            emailService.queueWeeklySummaryEmail(claim.userId(), result.headline());
            notifySummaryReady(claim);
        } catch (RuntimeException e) {
            log.error("Weekly summary {} failed on attempt {}; it will be retried", claim.id(), claim.attempt(), e);
            store.release(claim, config.maxAttempts(), e.toString());
        }
    }

    /** Saved and committed: tell the API so an open page shows it. Best effort: it is only a hint. */
    private void notifySummaryReady(WeeklySummaryStore.Claim claim) {
        try {
            eventNotifier.summaryReady(claim.userId());
        } catch (RuntimeException e) {
            log.warn("Could not send the live-update notification for summary {}", claim.id(), e);
        }
    }

    private static WeeklySummary.Result toResult(WeekStats stats, GeneratedSummary summary) {
        AiUsage usage = summary.usage();
        return new WeeklySummary.Result(
                stats.totalCompletions(),
                stats.totalXpEarned(),
                WeeklySummaryStore.truncate(summary.output().headline(), 120),
                WeeklySummaryStore.truncate(summary.output().body(), 600),
                summary.output().focusHabit(),
                summary.source(),
                WeeklySummaryStore.truncate(summary.fallbackReason(), 300),
                usage == null ? null : usage.model(),
                usage == null ? null : Math.toIntExact(usage.inputTokens()),
                usage == null ? null : Math.toIntExact(usage.outputTokens()),
                usage == null ? null : Math.toIntExact(usage.latencyMs()));
    }
}
