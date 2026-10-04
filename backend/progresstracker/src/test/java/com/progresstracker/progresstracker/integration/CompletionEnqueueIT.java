package com.progresstracker.progresstracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The async path: with the queue enabled, the API must only record the completion and publish
 * an event. Computing the reward is the worker's job, so no XP may be granted here.
 */
class CompletionEnqueueIT extends IntegrationTestBase {

    private static final String QUEUE_URL = LocalSqs.createQueue("completions-" + UUID.randomUUID());

    @DynamicPropertySource
    static void queueProperties(DynamicPropertyRegistry registry) {
        registry.add("queue.enabled", () -> "true");
        registry.add("queue.sqsUrl", () -> QUEUE_URL);
        registry.add("queue.endpointOverride", LocalSqs::endpoint);
    }

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void completionIsRecordedWithoutXpAndPublishedToTheQueue() {
        String email = uniqueEmail();
        String token = registerUser(email);
        long habitId = createDailyHabit(token, "Meditate");

        ResponseEntity<JsonNode> response = completeHabit(token, habitId);

        assertThat(response.getStatusCode().value()).isEqualTo(200);

        Map<String, Object> entry = jdbc.queryForMap(
                "select completed_date, xp_earned from habit_entries where habit_id = ?", habitId);
        assertThat(entry.get("xp_earned")).isEqualTo(0);
        assertThat(xpTotal(habitId)).isZero();

        JsonNode event = awaitEventFor(habitId);
        assertThat(event.get("userId").asLong()).isEqualTo(userIdFor(email));
        assertThat(event.get("date").asText()).isEqualTo(entry.get("completed_date").toString());
    }

    @Test
    void concurrentCompletionsRecordExactlyOneEntryAndStillPublish() throws Exception {
        String token = registerUser(uniqueEmail());
        long habitId = createDailyHabit(token, "Run");

        List<Integer> statuses = completeConcurrently(token, habitId, 20);

        assertThat(statuses).hasSize(20).containsOnly(200);
        assertThat(entryCount(habitId)).isEqualTo(1);
        assertThat(awaitEventFor(habitId)).isNotNull();
    }

    /** Polls the queue until an event for this habit shows up, discarding everything it reads. */
    private JsonNode awaitEventFor(long habitId) {
        AtomicReference<JsonNode> found = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            for (Message message : LocalSqs.receive(QUEUE_URL)) {
                JsonNode body = objectMapper.readTree(message.body());
                LocalSqs.delete(QUEUE_URL, message);
                if (body.path("habitId").asLong() == habitId) {
                    found.set(body);
                }
            }
            assertThat(found.get()).as("completion event for habit %d", habitId).isNotNull();
        });
        return found.get();
    }
}
