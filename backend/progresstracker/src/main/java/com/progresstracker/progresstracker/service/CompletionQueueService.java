package com.progresstracker.progresstracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;



import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Service
public class CompletionQueueService {
    private static final Logger log = LoggerFactory.getLogger(CompletionQueueService.class);


    private final ObjectMapper objectMapper;

    @Value("${queue.enabled:true}")
    private boolean enabled;

    @Value("${queue.sqsUrl:}")
    private String sqsUrl;

    @PostConstruct
    public void debugQueue() {
        System.out.println("DEBUG(queue.sqsUrl)=" + sqsUrl);
    }


    @Value("${queue.awsRegion:us-west-1}")
    private String awsRegion;

    private volatile SqsClient sqsClient;

    public CompletionQueueService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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

        log.info("ENQUEUE completion userId={} habitId={} date={} queueUrl={}", userId, habitId, date, sqsUrl);

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
