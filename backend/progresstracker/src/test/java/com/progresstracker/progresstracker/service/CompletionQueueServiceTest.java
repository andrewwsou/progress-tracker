package com.progresstracker.progresstracker.service;

import com.progresstracker.progresstracker.outbox.OutboxEvent;
import org.mockito.ArgumentMatchers;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResultEntry;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bad queue settings must stop the API at startup instead of silently dropping events later. */
class CompletionQueueServiceTest {

    private static CompletionQueueService service(boolean enabled, String sqsUrl, String endpointOverride) {
        CompletionQueueService service = new CompletionQueueService();
        ReflectionTestUtils.setField(service, "enabled", enabled);
        ReflectionTestUtils.setField(service, "sqsUrl", sqsUrl);
        ReflectionTestUtils.setField(service, "awsRegion", "us-west-1");
        ReflectionTestUtils.setField(service, "endpointOverride", endpointOverride);
        return service;
    }

    @Test
    void nothingIsRequiredWhenTheQueueIsDisabled() {
        assertThatCode(() -> service(false, "", "").validateConfiguration()).doesNotThrowAnyException();
    }

    @Test
    void anEnabledQueueNeedsAQueueUrl() {
        assertThatThrownBy(() -> service(true, " ", "").validateConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QUEUE_SQS_URL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqs:9324", "localhost:9324", "sqs", "http://bad host:9324", "ftp://sqs:9324"})
    void aMalformedEndpointOverrideIsRejected(String endpointOverride) {
        assertThatThrownBy(() -> service(true, "http://sqs:9324/000000000000/q", endpointOverride).validateConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QUEUE_ENDPOINT_OVERRIDE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "http://localhost:9324", " http://sqs:9324 ", "https://sqs.us-west-1.amazonaws.com"})
    void aBlankOrWellFormedEndpointOverrideIsAccepted(String endpointOverride) {
        assertThatCode(() -> service(true, "http://sqs:9324/000000000000/q", endpointOverride).validateConfiguration())
                .doesNotThrowAnyException();
    }

    @Test
    void publishReturnsOnlyTheEventsTheQueueAccepted() {
        CompletionQueueService service = service(true, "http://sqs:9324/000000000000/q", "");
        SqsClient client = mock(SqsClient.class);
        ReflectionTestUtils.setField(service, "sqsClient", client);
        OutboxEvent accepted = new OutboxEvent(UUID.randomUUID(), "habit.completed", "{}", OffsetDateTime.now());
        OutboxEvent rejected = new OutboxEvent(UUID.randomUUID(), "habit.completed", "{}", OffsetDateTime.now());
        // SQS can answer 200 and still reject individual entries.
        when(client.sendMessageBatch(ArgumentMatchers.<Consumer<SendMessageBatchRequest.Builder>>any()))
                .thenReturn(SendMessageBatchResponse.builder()
                        .successful(SendMessageBatchResultEntry.builder().id(accepted.getId().toString()).build())
                        .failed(BatchResultErrorEntry.builder()
                                .id(rejected.getId().toString()).code("InternalError").senderFault(false).build())
                        .build());

        assertThat(service.publish(List.of(accepted, rejected))).containsExactly(accepted.getId());
    }
}
