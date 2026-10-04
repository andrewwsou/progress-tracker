package com.progresstracker.progresstracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bad queue settings must stop the API at startup instead of silently dropping events later. */
class CompletionQueueServiceTest {

    private static CompletionQueueService service(boolean enabled, String sqsUrl, String endpointOverride) {
        CompletionQueueService service = new CompletionQueueService(new ObjectMapper());
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
}
