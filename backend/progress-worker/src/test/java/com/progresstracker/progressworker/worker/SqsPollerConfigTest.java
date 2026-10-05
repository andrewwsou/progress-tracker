package com.progresstracker.progressworker.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.progresstracker.progressworker.events.HabitEventNotifier;
import com.progresstracker.progressworker.service.CompletionProcessor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Bad queue settings must stop the worker at startup with a message that names the variable. */
class SqsPollerConfigTest {

    private static SqsPoller poller(boolean queueEnabled, String sqsUrl, String endpointOverride) {
        SqsPoller poller = new SqsPoller(new ObjectMapper(), mock(CompletionProcessor.class), mock(HabitEventNotifier.class), new SimpleMeterRegistry());
        ReflectionTestUtils.setField(poller, "workerEnabled", true);
        ReflectionTestUtils.setField(poller, "queueEnabled", queueEnabled);
        ReflectionTestUtils.setField(poller, "sqsUrl", sqsUrl);
        ReflectionTestUtils.setField(poller, "awsRegion", "us-west-1");
        ReflectionTestUtils.setField(poller, "endpointOverride", endpointOverride);
        return poller;
    }

    @Test
    void doesNotPollWhenTheQueueIsDisabled() {
        assertThatCode(() -> poller(false, "", "").start()).doesNotThrowAnyException();
    }

    @Test
    void anEnabledQueueNeedsAQueueUrl() {
        assertThatThrownBy(() -> poller(true, " ", "").start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QUEUE_SQS_URL");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 9})
    void concurrencyMustLeaveTwoDatabaseConnectionsSpare(int concurrency) {
        SqsPoller poller = poller(true, "http://sqs:9324/000000000000/q", "");
        ReflectionTestUtils.setField(poller, "concurrency", concurrency);
        ReflectionTestUtils.setField(poller, "databasePoolSize", 10);

        assertThatThrownBy(poller::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WORKER_CONCURRENCY must be between 1 and 8");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqs:9324", "localhost:9324", "sqs", "http://bad host:9324", "ftp://sqs:9324"})
    void aMalformedEndpointOverrideIsRejected(String endpointOverride) {
        assertThatThrownBy(() -> poller(true, "http://sqs:9324/000000000000/q", endpointOverride).start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QUEUE_ENDPOINT_OVERRIDE");
    }
}
