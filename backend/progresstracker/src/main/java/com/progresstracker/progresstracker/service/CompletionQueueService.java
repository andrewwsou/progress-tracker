package com.progresstracker.progresstracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.net.URI;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class CompletionQueueService {
    private static final Logger log = LoggerFactory.getLogger(CompletionQueueService.class);

    private final ObjectMapper objectMapper;

    // Sends run off the request thread so a slow/blocking SQS network call
    // (real round trip to AWS) never adds latency to the API response.
    private final ExecutorService enqueueExecutor = Executors.newFixedThreadPool(4);

    @Value("${queue.enabled:true}")
    private boolean enabled;

    @Value("${queue.sqsUrl:}")
    private String sqsUrl;

    @Value("${queue.awsRegion:us-west-1}")
    private String awsRegion;

    // Blank in production (the SDK resolves the real AWS endpoint). Set it to point the
    // client at a local SQS-compatible broker in integration tests and Docker Compose.
    @Value("${queue.endpointOverride:}")
    private String endpointOverride;

    private volatile SqsClient sqsClient;

    public CompletionQueueService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Fails startup on bad queue settings. Without this the API would start, report healthy,
     * and only log an error on a background thread each time a completion could not be published.
     */
    @PostConstruct
    void validateConfiguration() {
        if (!enabled) {
            return;
        }
        if (sqsUrl == null || sqsUrl.isBlank()) {
            throw new IllegalStateException("QUEUE_SQS_URL must be set when QUEUE_ENABLED=true");
        }
        getClient();
    }

    public void enqueueCompletion(Long userId, Long habitId, LocalDate date) {
        if (!enabled) {
            log.info("SQS enqueue skipped (queue.enabled=false) userId={} habitId={} date={}", userId, habitId, date);
            return;
        }
        if (sqsUrl == null || sqsUrl.isBlank()) {
            throw new IllegalStateException("queue.sqsUrl must be set when queue.enabled=true");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", userId);
        payload.put("habitId", habitId);
        payload.put("date", date.toString());

        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize SQS payload", e);
        }

        enqueueExecutor.submit(() -> {
            try {
                getClient().sendMessage(SendMessageRequest.builder()
                        .queueUrl(sqsUrl)
                        .messageBody(body)
                        .build());
                log.info("ENQUEUE completion succeeded userId={} habitId={} date={}", userId, habitId, date);
            } catch (Exception e) {
                log.error("ENQUEUE completion failed userId={} habitId={} date={}", userId, habitId, date, e);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        enqueueExecutor.shutdown();
    }

    private SqsClient getClient() {
        SqsClient c = this.sqsClient;
        if (c != null) return c;

        synchronized (this) {
            if (this.sqsClient == null) {
                SqsClientBuilder builder = SqsClient.builder()
                        .region(Region.of(awsRegion))
                        .credentialsProvider(DefaultCredentialsProvider.create());
                if (endpointOverride != null && !endpointOverride.isBlank()) {
                    builder.endpointOverride(parseEndpoint(endpointOverride));
                }
                this.sqsClient = builder.build();
            }
            return this.sqsClient;
        }
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
}
