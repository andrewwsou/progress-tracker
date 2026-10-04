package com.progresstracker.progresstracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bad input is refused, and the controllers' errors come back in one shape: an RFC 9457
 * problem document, with per-field messages for validation failures.
 */
@TestPropertySource(properties = "queue.enabled=false")
class RequestValidationIT extends IntegrationTestBase {

    @Test
    void aHabitWithABlankNameIsRejectedWithAFieldError() {
        String email = uniqueEmail();
        String token = registerUser(email);

        ResponseEntity<JsonNode> response = send(HttpMethod.POST, "/api/habits", token,
                Map.of("name", "   ", "description", "", "frequency", "DAILY"));

        assertProblem(response, 400);
        assertThat(response.getBody().get("errors").has("name")).isTrue();
        assertThat(habitCountFor(email)).isZero();
    }

    @Test
    void aHabitWithAnOutOfRangeGoalIsRejected() {
        String email = uniqueEmail();
        String token = registerUser(email);

        for (int goal : new int[]{0, -1, 366}) {
            ResponseEntity<JsonNode> response = send(HttpMethod.POST, "/api/habits", token,
                    Map.of("name", "Read", "frequency", "DAILY", "goalTargetCount", goal));

            assertProblem(response, 400);
            assertThat(response.getBody().get("errors").has("goalTargetCount")).isTrue();
        }
        assertThat(habitCountFor(email)).isZero();
    }

    @Test
    void aHabitWithAnUnknownFrequencyOrMalformedJsonIsRejected() {
        String email = uniqueEmail();
        String token = registerUser(email);

        assertProblem(send(HttpMethod.POST, "/api/habits", token,
                Map.of("name", "Read", "frequency", "HOURLY")), 400);
        assertProblem(send(HttpMethod.POST, "/api/habits", token, "{ this is not json"), 400);
        assertProblem(send(HttpMethod.POST, "/api/habits", token, Map.of("name", "No frequency")), 400);

        assertThat(habitCountFor(email)).isZero();
    }

    @Test
    void aHabitWithOnlyTheRequiredFieldsGetsSensibleDefaults() {
        String token = registerUser(uniqueEmail());

        ResponseEntity<JsonNode> response = send(HttpMethod.POST, "/api/habits", token,
                Map.of("name", "Review the week", "frequency", "WEEKLY"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode habit = response.getBody();
        assertThat(habit.get("goalTargetCount").asInt()).isEqualTo(1);
        assertThat(habit.get("goalPeriod").asText()).isEqualTo("WEEKLY");
        assertThat(habit.get("xpTotal").asInt()).isZero();
        assertThat(habit.get("createdAt").asText()).matches(".*(Z|[+-]\\d{2}:\\d{2})$"); // carries a UTC offset
        assertThat(habit.has("lastCompletedDate")).isFalse(); // no value yet: omitted, not null
        assertThat(habit.has("user")).isFalse();
    }

    @Test
    void textTheDatabaseCannotStoreIsABadRequestNotAConflict() {
        String email = uniqueEmail();
        String token = registerUser(email);

        // PostgreSQL refuses a NUL character in text. That is invalid input, not a conflict.
        assertProblem(send(HttpMethod.POST, "/api/habits", token,
                Map.of("name", "bad\u0000name", "frequency", "DAILY")), 400);
        assertProblem(send(HttpMethod.POST, "/api/auth/login", null,
                Map.of("email", "bad\u0000email", "password", "any-password")), 400);

        assertThat(habitCountFor(email)).isZero();
    }

    @Test
    void registrationRejectsAnInvalidEmailOrPasswordAndCreatesNoAccount() {
        ResponseEntity<JsonNode> badEmail = register("not-an-email", "long-enough-password");
        assertProblem(badEmail, 400);
        assertThat(badEmail.getBody().get("errors").has("email")).isTrue();

        // Too short, whitespace only, and 25 characters that are 75 bytes (over bcrypt's 72-byte limit).
        String[] badPasswords = {"short", "            ", "\u5bc6".repeat(25)};
        String[] emails = {uniqueEmail(), uniqueEmail(), uniqueEmail()};
        for (int i = 0; i < badPasswords.length; i++) {
            ResponseEntity<JsonNode> response = register(emails[i], badPasswords[i]);
            assertProblem(response, 400);
            assertThat(response.getBody().get("errors").has("password")).isTrue();
        }

        String missingPasswordEmail = uniqueEmail();
        Map<String, Object> missingPassword = new HashMap<>();
        missingPassword.put("email", missingPasswordEmail);
        assertProblem(send(HttpMethod.POST, "/api/auth/register", null, missingPassword), 400);

        assertThat(jdbc.queryForObject(
                "select count(*) from app_user where email in (?, ?, ?, ?, ?)", Integer.class,
                "not-an-email", emails[0], emails[1], emails[2], missingPasswordEmail)).isZero();
    }

    @Test
    void registeringAnEmailTwiceIsAConflict() {
        String email = uniqueEmail();
        registerUser(email);

        ResponseEntity<JsonNode> second = register(email, "another-long-password");

        assertProblem(second, 409);
        assertThat(second.getBody().get("detail").asText()).isEqualTo("Email already used");
    }

    @Test
    void loggingInWithTheWrongPasswordIs401() {
        String email = uniqueEmail();
        registerUser(email);

        ResponseEntity<JsonNode> response = send(HttpMethod.POST, "/api/auth/login", null,
                Map.of("email", email, "password", "definitely-the-wrong-password"));

        assertProblem(response, 401);
        assertThat(response.getBody().get("detail").asText()).isEqualTo("Invalid credentials");
    }

    @Test
    void anUnknownHabitIs404() {
        String token = registerUser(uniqueEmail());

        assertProblem(completeHabit(token, 999_999_999L), 404);
        assertProblem(send(HttpMethod.POST, "/api/habits/not-a-number/complete", token, null), 400);
    }

    private ResponseEntity<JsonNode> register(String email, String password) {
        return send(HttpMethod.POST, "/api/auth/register", null, Map.of("email", email, "password", password));
    }

    private int habitCountFor(String email) {
        return jdbc.queryForObject("select count(*) from habit where user_id = ?", Integer.class, userIdFor(email));
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, int status) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().get("status").asInt()).isEqualTo(status);
        assertThat(response.getBody().hasNonNull("title")).isTrue();
    }
}
