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
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.LocalDate;
import java.util.List;
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

    @Value("${queue.awsRegion:us-west-2}")
    private String awsRegion;

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
            throw new IllegalStateException("queue.sqsUrl must be set");
        }

        this.sqsClient = SqsClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();

        running.set(true);
        loopThread = new Thread(this::pollLoop, "sqs-poller");
        loopThread.setDaemon(false);

        loopThread.start();
        log.info("SQS poller started");
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
        try {
            JsonNode node = objectMapper.readTree(message.body());

            // Use path() so missing fields don't NPE
            JsonNode userIdNode = node.path("userId");
            JsonNode habitIdNode = node.path("habitId");

            // Accept either "date" or "completedDate"
            String dateStr = node.hasNonNull("date")
                    ? node.get("date").asText()
                    : node.path("completedDate").asText(null);

            // Validate
            if (userIdNode.isMissingNode() || habitIdNode.isMissingNode() || dateStr == null || dateStr.isBlank()) {
                log.error("Invalid message schema (deleting message): {}", message.body());
                delete(message); // dev-friendly: don't poison the queue
                return;
            }

            long userId = userIdNode.asLong();
            long habitId = habitIdNode.asLong();
            LocalDate date = LocalDate.parse(dateStr);

            completionProcessor.process(userId, habitId, date);

            delete(message);
            log.info("Processed completion userId={} habitId={} date={}", userId, habitId, date);

        } catch (Exception e) {
            log.error("Failed processing message (deleting message to avoid retry poison): {}", message.body(), e);
            delete(message); // dev-friendly; for prod you'd DLQ instead
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
