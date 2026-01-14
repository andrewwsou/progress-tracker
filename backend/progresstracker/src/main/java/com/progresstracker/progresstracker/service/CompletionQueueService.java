package com.progresstracker.progresstracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Service
public class CompletionQueueService {

    private final ObjectMapper objectMapper;

    @Value("${queue.enabled:false}")
    private boolean enabled;

    @Value("${queue.sqsUrl:}")
    private String sqsUrl;

    @Value("${queue.awsRegion:us-west-2}")
    private String awsRegion;

    private volatile SqsClient sqsClient;

    public CompletionQueueService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void enqueueCompletion(Long userId, Long habitId, LocalDate date) {
        if (!enabled) {
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

        getClient().sendMessage(SendMessageRequest.builder()
                .queueUrl(sqsUrl)
                .messageBody(body)
                .build());
    }

    private SqsClient getClient() {
        SqsClient c = this.sqsClient;
        if (c != null) return c;

        synchronized (this) {
            if (this.sqsClient == null) {
                this.sqsClient = SqsClient.builder()
                        .region(Region.of(awsRegion))
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .build();
            }
            return this.sqsClient;
        }
    }
}
