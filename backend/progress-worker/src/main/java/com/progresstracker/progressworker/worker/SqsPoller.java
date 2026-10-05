package com.progresstracker.progressworker.worker;

import com.progresstracker.progressworker.events.HabitEventNotifier;
import com.progresstracker.progressworker.service.CompletionProcessor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reads completion events from SQS and hands each one to a fixed pool of worker threads.
 *
 * <ul>
 *   <li>Backpressure: it asks SQS for at most as many messages as there are idle workers, so a
 *       received message never waits in memory while its visibility timeout runs down.</li>
 *   <li>A message is deleted once its reward has committed. If processing fails it is left alone:
 *       SQS redelivers it, and after the queue's maxReceiveCount moves it to the dead-letter queue.</li>
 *   <li>Graceful stop: polling stops first, the messages in flight finish (up to a deadline), and
 *       only then is the SQS client closed. Anything cut off is redelivered by SQS.</li>
 * </ul>
 *
 * Processing in parallel is safe: {@link CompletionProcessor} applies one user's rewards one at a
 * time with a row lock, and ignores an event it has already handled.
 */
@Component
public class SqsPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SqsPoller.class);

    private static final long MAX_POLL_BACKOFF_MS = 30_000;

    private final JsonMapper jsonMapper;
    private final CompletionProcessor completionProcessor;
    private final HabitEventNotifier eventNotifier;

    @Value("${worker.enabled:true}")
    private boolean workerEnabled;

    @Value("${queue.enabled:true}")
    private boolean queueEnabled;

    @Value("${queue.sqsUrl:}")
    private String sqsUrl;

    @Value("${queue.awsRegion:us-west-1}")
    private String awsRegion;

    // Blank in production (the SDK resolves the real AWS endpoint). Set it to point the
    // client at a local SQS-compatible broker in integration tests and Docker Compose.
    @Value("${queue.endpointOverride:}")
    private String endpointOverride;

    @Value("${worker.waitTimeSeconds:20}")
    private int waitTimeSeconds = 20;

    @Value("${worker.maxMessages:10}")
    private int maxMessages = 10;

    @Value("${worker.visibilityTimeoutSeconds:60}")
    private int visibilityTimeoutSeconds = 60;

    @Value("${worker.concurrency:8}")
    private int concurrency = 8;

    @Value("${worker.shutdownTimeoutSeconds:25}")
    private int shutdownTimeoutSeconds = 25;

    @Value("${spring.datasource.hikari.maximum-pool-size:10}")
    private int databasePoolSize = 10;

    /** First pause after a failed poll. It doubles with each failure in a row, up to 30 s. */
    long pollErrorBackoffMs = 1_000;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile long lastPollAtMillis = System.currentTimeMillis();
    private SqsClient sqsClient;
    // Built by start() unless a test passed one in. One we built is closed and rebuilt on a
    // stop and start (a test context that is paused and resumed does exactly that).
    private final boolean ownsClient;
    private Thread loopThread;
    private Semaphore idleWorkers;
    private ExecutorService workers;
    private CountDownLatch stopRequested;

    private final Counter applied;
    private final Counter skipped;
    private final Counter invalid;
    private final Counter failed;
    private final Counter pollErrors;
    private final Timer processing;
    private final Timer lag;

    @Autowired
    public SqsPoller(JsonMapper jsonMapper, CompletionProcessor completionProcessor, HabitEventNotifier eventNotifier,
                     MeterRegistry meterRegistry) {
        this(jsonMapper, completionProcessor, eventNotifier, meterRegistry, null);
    }

    /** For tests: uses the given client instead of building one. */
    SqsPoller(JsonMapper jsonMapper, CompletionProcessor completionProcessor, HabitEventNotifier eventNotifier,
              MeterRegistry meterRegistry, SqsClient sqsClient) {
        this.jsonMapper = jsonMapper;
        this.completionProcessor = completionProcessor;
        this.eventNotifier = eventNotifier;
        this.sqsClient = sqsClient;
        this.ownsClient = sqsClient == null;

        this.applied = events(meterRegistry, "applied");
        this.skipped = events(meterRegistry, "skipped");
        this.invalid = events(meterRegistry, "invalid");
        this.failed = events(meterRegistry, "failed");
        this.pollErrors = Counter.builder("worker.poll.errors")
                .description("Failed requests for messages")
                .register(meterRegistry);
        this.processing = Timer.builder("worker.event.processing")
                .description("Time to apply one reward, transaction included")
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.lag = Timer.builder("worker.event.lag")
                .description("From the API recording a completion to the worker committing its reward")
                .publishPercentileHistogram()
                .maximumExpectedValue(Duration.ofMinutes(30)) // backlogs and redeliveries run to minutes
                .register(meterRegistry);
        Gauge.builder("worker.inflight", inFlight, AtomicInteger::get)
                .description("Messages being processed right now")
                .register(meterRegistry);
    }

    private static Counter events(MeterRegistry registry, String outcome) {
        return Counter.builder("worker.events")
                .description("Completion events handled, by outcome")
                .tag("outcome", outcome)
                .register(registry);
    }

    @Override
    public void start() {
        if (!workerEnabled) {
            log.info("Worker disabled (worker.enabled=false)");
            return;
        }
        if (!queueEnabled) {
            log.info("Queue disabled (queue.enabled=false)");
            return;
        }
        if (sqsUrl == null || sqsUrl.isBlank()) {
            throw new IllegalStateException("QUEUE_SQS_URL must be set when QUEUE_ENABLED=true");
        }
        // Every worker holds a database connection while it runs. Leave one for the weekly
        // summary job and one for health checks, or workers would queue for connections.
        int maxConcurrency = databasePoolSize - 2;
        if (concurrency < 1 || concurrency > maxConcurrency) {
            throw new IllegalStateException("WORKER_CONCURRENCY must be between 1 and " + maxConcurrency
                    + " (DB_POOL_SIZE " + databasePoolSize + " minus 2), but was " + concurrency);
        }

        if (sqsClient == null) {
            SqsClientBuilder builder = SqsClient.builder()
                    .region(Region.of(awsRegion))
                    .credentialsProvider(DefaultCredentialsProvider.builder().build())
                    // A cap on each call, retries included, so a queue that accepts connections but
                    // never answers cannot hold the poll loop (or a worker's delete) for minutes.
                    .overrideConfiguration(o -> o.apiCallTimeout(Duration.ofSeconds(waitTimeSeconds + 10L)));
            if (endpointOverride != null && !endpointOverride.isBlank()) {
                builder.endpointOverride(parseEndpoint(endpointOverride));
            }
            sqsClient = builder.build();
        }

        idleWorkers = new Semaphore(concurrency);
        workers = Executors.newFixedThreadPool(concurrency, new CustomizableThreadFactory("sqs-worker-"));
        stopRequested = new CountDownLatch(1);
        lastPollAtMillis = System.currentTimeMillis();
        running.set(true);
        loopThread = new Thread(this::pollLoop, "sqs-poller");
        loopThread.start();
        log.info("SQS poller started: up to {} messages at a time", concurrency);
    }

    private static URI parseEndpoint(String value) {
        try {
            URI uri = URI.create(value.strip());
            boolean http = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
            if (http && uri.getHost() != null) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
            // reported below
        }
        throw new IllegalStateException(
                "QUEUE_ENDPOINT_OVERRIDE must be an absolute http(s) URL such as http://localhost:9324, but was: " + value);
    }

    private void pollLoop() {
        long backoffMs = 0;
        while (running.get()) {
            int reserved = 0;
            try {
                // Wait for an idle worker, then ask for no more messages than there are idle workers.
                idleWorkers.acquire();
                reserved = 1;
                while (reserved < maxMessages && idleWorkers.tryAcquire()) {
                    reserved++;
                }
                if (!running.get()) {
                    break;
                }

                List<Message> messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(sqsUrl)
                        .waitTimeSeconds(waitTimeSeconds)
                        .maxNumberOfMessages(reserved)
                        .visibilityTimeout(visibilityTimeoutSeconds)
                        .build()).messages();
                for (Message message : messages) {
                    dispatch(message); // the message now owns one of the reserved workers
                    reserved--;
                }
                backoffMs = 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (!running.get()) {
                    break;
                }
                pollErrors.increment();
                backoffMs = backoffMs == 0 ? pollErrorBackoffMs : Math.min(backoffMs * 2, MAX_POLL_BACKOFF_MS);
                log.error("SQS poll failed; trying again in {} ms", backoffMs, e);
                if (awaitStop(backoffMs)) {
                    break;
                }
            } finally {
                idleWorkers.release(reserved); // workers reserved for messages that did not arrive
                lastPollAtMillis = System.currentTimeMillis();
            }
        }
    }

    private void dispatch(Message message) {
        inFlight.incrementAndGet();
        try {
            workers.execute(() -> {
                try {
                    handleMessage(message);
                } finally {
                    inFlight.decrementAndGet();
                    idleWorkers.release();
                }
            });
        } catch (RejectedExecutionException e) {
            // Only happens while stopping. The message stays on the queue and SQS redelivers it.
            inFlight.decrementAndGet();
            idleWorkers.release();
            log.warn("Stopping, so not processing message {}; SQS will redeliver it", message.messageId());
        }
    }

    /** Pauses for the given time, or until a stop is requested. Returns true if stopping. */
    private boolean awaitStop(long millis) {
        try {
            return stopRequested.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private void handleMessage(Message message) {
        UUID eventId;
        long userId;
        long habitId;
        LocalDate date;
        OffsetDateTime occurredAt;

        // Schema errors can never succeed on retry, so these are poison messages: delete them.
        try {
            JsonNode node = jsonMapper.readTree(message.body());

            // Use path() so missing fields don't NPE
            JsonNode userIdNode = node.path("userId");
            JsonNode habitIdNode = node.path("habitId");

            // Accept either "date" or "completedDate"
            String dateStr = node.hasNonNull("date")
                    ? node.get("date").asString()
                    : node.path("completedDate").asString(null);

            if (!isLongId(userIdNode) || !isLongId(habitIdNode) || dateStr == null || dateStr.isBlank()) {
                log.error("Invalid message schema (deleting message): {}", message.body());
                invalid.increment();
                delete(message);
                return;
            }

            userId = userIdNode.asLong();
            habitId = habitIdNode.asLong();
            date = LocalDate.parse(dateStr);

            eventId = parseEventId(node, habitId, date);
            occurredAt = parseOccurredAt(node);
        } catch (Exception e) {
            log.error("Unparseable message (deleting message): {}", message.body(), e);
            invalid.increment();
            delete(message);
            return;
        }

        // Processing failures (DB blips, transient errors) are different: leave the message
        // alone so SQS redelivers it after the visibility timeout expires. This is safe because
        // CompletionProcessor.process() is idempotent per event - a retried delivery either
        // finishes the original attempt or is a no-op, never a duplicate reward.
        try {
            long start = System.nanoTime();
            boolean rewarded = completionProcessor.process(eventId, userId, habitId, date, occurredAt);
            long elapsedNanos = System.nanoTime() - start;
            double elapsedMs = elapsedNanos / 1_000_000.0;
            processing.record(elapsedNanos, TimeUnit.NANOSECONDS);
            delete(message);
            if (rewarded) {
                applied.increment();
                if (occurredAt != null) {
                    Duration sinceCompletion = Duration.between(occurredAt, OffsetDateTime.now());
                    if (!sinceCompletion.isNegative()) {
                        lag.record(sinceCompletion);
                    }
                }
                log.info("Processed completion eventId={} userId={} habitId={} date={} elapsedMs={}",
                        eventId, userId, habitId, date, elapsedMs);
                notifyRewardApplied(userId, habitId);
            } else {
                skipped.increment();
                log.info("No reward applied eventId={} habitId={} date={} (already handled, or habit deleted)",
                        eventId, habitId, date);
            }
        } catch (Exception e) {
            failed.increment();
            log.error("Failed processing completion eventId={} userId={} habitId={} date={} (leaving message for retry): {}",
                    eventId, userId, habitId, date, e.getMessage(), e);
        }
    }

    /** The reward has committed; tell the API so open pages update. Best effort: it is only a hint. */
    private void notifyRewardApplied(long userId, long habitId) {
        try {
            eventNotifier.rewardApplied(userId, habitId);
        } catch (Exception e) {
            log.warn("Could not send the live-update notification for habit {}", habitId, e);
        }
    }

    /**
     * An id must be a JSON integer that fits in a long. A missing id, null, a string (even "5") or
     * an object can never succeed on a retry, so it makes the message invalid.
     */
    private static boolean isLongId(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong();
    }

    /**
     * The user, habit and date are what identify a completion. If those are valid the event is
     * processed, even when its id is missing or unreadable: it then gets a stable id derived from
     * the completion itself, so repeats of it are still recognised.
     */
    private static UUID parseEventId(JsonNode node, long habitId, LocalDate date) {
        if (node.hasNonNull("eventId")) {
            try {
                return UUID.fromString(node.get("eventId").asString(""));
            } catch (IllegalArgumentException e) {
                log.warn("Unreadable eventId '{}'; deriving one from habit {} and date {}",
                        node.get("eventId").asString(""), habitId, date);
            }
        }
        return UUID.nameUUIDFromBytes(("completion:" + habitId + ":" + date).getBytes(StandardCharsets.UTF_8));
    }

    /** Only used for reporting, so a missing or unreadable value is never a reason to drop the event. */
    private static OffsetDateTime parseOccurredAt(JsonNode node) {
        if (!node.hasNonNull("occurredAt")) {
            return null;
        }
        try {
            return OffsetDateTime.parse(node.get("occurredAt").asString(""));
        } catch (DateTimeParseException e) {
            log.warn("Unreadable occurredAt '{}'; recording the event without it", node.get("occurredAt").asString(""));
            return null;
        }
    }

    private void delete(Message message) {
        try {
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(sqsUrl)
                    .receiptHandle(message.receiptHandle())
                    .build());
        } catch (Exception ex) {
            log.error("Failed deleting message", ex);
        }
    }

    /**
     * Stops taking new messages, lets the current poll return and its messages finish, and closes
     * the client last. Runs before the web server and the database pool shut down, because this
     * bean is in the last lifecycle phase to start and so the first to stop.
     */
    @Override
    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(shutdownTimeoutSeconds);
        stopRequested.countDown(); // wakes a poll loop that is backing off after an error
        try {
            // join(0) would wait forever, hence at least 1 ms.
            loopThread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            if (loopThread.isAlive()) {
                loopThread.interrupt();
            }
            workers.shutdown();
            if (!workers.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                log.warn("{} messages still in flight after {} s; stopping them, and SQS will redeliver them",
                        inFlight.get(), shutdownTimeoutSeconds);
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            sqsClient.close();
            if (ownsClient) {
                sqsClient = null;
            }
            log.info("SQS poller stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    boolean loopAlive() {
        return loopThread != null && loopThread.isAlive();
    }

    long millisSinceLastPoll() {
        return System.currentTimeMillis() - lastPollAtMillis;
    }

    int inFlight() {
        return inFlight.get();
    }

    int concurrency() {
        return concurrency;
    }
}
