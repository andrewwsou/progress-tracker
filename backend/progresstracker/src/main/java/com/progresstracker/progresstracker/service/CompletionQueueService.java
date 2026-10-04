package com.progresstracker.progresstracker.service;

import com.progresstracker.progresstracker.outbox.OutboxEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** The API's connection to the completion queue. Used by the outbox relay, never on a request thread. */
@Service
public class CompletionQueueService {
    private static final Logger log = LoggerFactory.getLogger(CompletionQueueService.class);

    @Value("${queue.enabled:false}")
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

    /**
     * Fails startup on bad queue settings. Without this the API would start, report healthy,
     * and only discover the problem when the relay first tried to publish.
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

    /**
     * Sends up to 10 events in one batch call.
     *
     * @return the ids of the events the queue accepted; anything missing must be sent again
     */
    public Set<UUID> publish(List<OutboxEvent> events) {
        List<SendMessageBatchRequestEntry> entries = events.stream()
                .map(event -> SendMessageBatchRequestEntry.builder()
                        .id(event.getId().toString())
                        .messageBody(event.getPayload())
                        .build())
                .toList();

        SendMessageBatchResponse response = getClient().sendMessageBatch(request -> request
                .queueUrl(sqsUrl)
                .entries(entries));

        response.failed().forEach(failure ->
                log.warn("Queue rejected event {}: {} {}", failure.id(), failure.code(), failure.message()));

        return response.successful().stream()
                .map(success -> UUID.fromString(success.id()))
                .collect(Collectors.toSet());
    }

    @PreDestroy
    public void shutdown() {
        SqsClient client = this.sqsClient;
        if (client != null) {
            client.close();
        }
    }

    private SqsClient getClient() {
        SqsClient c = this.sqsClient;
        if (c != null) return c;

        synchronized (this) {
            if (this.sqsClient == null) {
                SqsClientBuilder builder = SqsClient.builder()
                        .region(Region.of(awsRegion))
                        .credentialsProvider(DefaultCredentialsProvider.builder().build())
                        // The relay holds database row locks while it sends, so a stuck network
                        // call must give up rather than hold them indefinitely.
                        .overrideConfiguration(config -> config
                                .apiCallAttemptTimeout(Duration.ofSeconds(5))
                                .apiCallTimeout(Duration.ofSeconds(15)));
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
