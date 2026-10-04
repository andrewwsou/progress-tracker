package com.progresstracker.progressworker.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.progresstracker.progressworker.service.CompletionProcessor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class SqsPoller {

    private static final Logger log = LoggerFactory.getLogger(SqsPoller.class);

    private final ObjectMapper objectMapper;
    private final CompletionProcessor completionProcessor;

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
    private int waitTimeSeconds;

    @Value("${worker.maxMessages:10}")
    private int maxMessages;

    @Value("${worker.visibilityTimeoutSeconds:60}")
    private int visibilityTimeoutSeconds;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread loopThread;
    private SqsClient sqsClient;

    public SqsPoller(ObjectMapper objectMapper, CompletionProcessor completionProcessor) {
        this.objectMapper = objectMapper;
        this.completionProcessor = completionProcessor;
    }

    @PostConstruct
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

        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (endpointOverride != null && !endpointOverride.isBlank()) {
            builder.endpointOverride(parseEndpoint(endpointOverride));
        }
        this.sqsClient = builder.build();

        running.set(true);
        loopThread = new Thread(this::pollLoop, "sqs-poller");
        loopThread.setDaemon(false);

        loopThread.start();
        log.info("SQS poller started");
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
        while (running.get()) {
            try {
                ReceiveMessageRequest req = ReceiveMessageRequest.builder()
                        .queueUrl(sqsUrl)
                        .waitTimeSeconds(waitTimeSeconds)
                        .maxNumberOfMessages(maxMessages)
                        .visibilityTimeout(visibilityTimeoutSeconds)
                        .build();

                List<Message> messages = sqsClient.receiveMessage(req).messages();
                for (Message m : messages) {
                    handleMessage(m);
                }
            } catch (Exception e) {
                log.error("SQS poll loop error", e);
                sleep(2000);
            }
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
            JsonNode node = objectMapper.readTree(message.body());

            // Use path() so missing fields don't NPE
            JsonNode userIdNode = node.path("userId");
            JsonNode habitIdNode = node.path("habitId");

            // Accept either "date" or "completedDate"
            String dateStr = node.hasNonNull("date")
                    ? node.get("date").asText()
                    : node.path("completedDate").asText(null);

            if (userIdNode.isMissingNode() || habitIdNode.isMissingNode() || dateStr == null || dateStr.isBlank()) {
                log.error("Invalid message schema (deleting message): {}", message.body());
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
            delete(message);
            return;
        }

        // Processing failures (DB blips, transient errors) are different: leave the message
        // alone so SQS redelivers it after the visibility timeout expires. This is safe because
        // CompletionProcessor.process() is idempotent per event - a retried delivery either
        // finishes the original attempt or is a no-op, never a duplicate reward.
        try {
            boolean applied = completionProcessor.process(eventId, userId, habitId, date, occurredAt);
            delete(message);
            if (applied) {
                log.info("Processed completion eventId={} userId={} habitId={} date={}", eventId, userId, habitId, date);
            } else {
                log.info("No reward applied eventId={} habitId={} date={} (already handled, or habit deleted)",
                        eventId, habitId, date);
            }
        } catch (Exception e) {
            log.error("Failed processing completion eventId={} userId={} habitId={} date={} (leaving message for retry): {}",
                    eventId, userId, habitId, date, e.getMessage(), e);
        }
    }

    /**
     * The user, habit and date are what identify a completion. If those are valid the event is
     * processed, even when its id is missing or unreadable: it then gets a stable id derived from
     * the completion itself, so repeats of it are still recognised.
     */
    private static UUID parseEventId(JsonNode node, long habitId, LocalDate date) {
        if (node.hasNonNull("eventId")) {
            try {
                return UUID.fromString(node.get("eventId").asText());
            } catch (IllegalArgumentException e) {
                log.warn("Unreadable eventId '{}'; deriving one from habit {} and date {}",
                        node.get("eventId").asText(), habitId, date);
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
            return OffsetDateTime.parse(node.get("occurredAt").asText());
        } catch (DateTimeParseException e) {
            log.warn("Unreadable occurredAt '{}'; recording the event without it", node.get("occurredAt").asText());
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


    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (loopThread != null) {
            try { loopThread.join(1500); } catch (InterruptedException ignored) {}
        }
        if (sqsClient != null) {
            sqsClient.close();
        }
        log.info("SQS poller stopped");
    }
}
