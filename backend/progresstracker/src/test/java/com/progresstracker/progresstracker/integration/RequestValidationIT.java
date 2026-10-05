package com.progresstracker.progresstracker.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
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

    /** A habit counts once a day or once a week, so some goals could never be met. */
    @Test
    void aGoalThatCanNeverBeMetIsRejected() {
        String email = uniqueEmail();
        String token = registerUser(email);

        List<Map<String, Object>> unreachable = List.of(
                Map.of("name", "Water", "frequency", "DAILY", "goalPeriod", "DAILY", "goalTargetCount", 3),
                Map.of("name", "Review", "frequency", "WEEKLY", "goalPeriod", "WEEKLY", "goalTargetCount", 2),
                Map.of("name", "Review", "frequency", "WEEKLY", "goalPeriod", "DAILY", "goalTargetCount", 1),
                Map.of("name", "Review", "frequency", "WEEKLY", "goalTargetCount", 2), // period defaults to weekly
                Map.of("name", "Run", "frequency", "DAILY", "goalPeriod", "WEEKLY", "goalTargetCount", 8));
        for (Map<String, Object> habit : unreachable) {
            assertProblem(send(HttpMethod.POST, "/api/habits", token, habit), 400);
        }
        assertThat(habitCountFor(email)).isZero();

        // Every goal the form offers is accepted.
        List<Map<String, Object>> reachable = List.of(
                Map.of("name", "Read", "frequency", "DAILY", "goalPeriod", "DAILY", "goalTargetCount", 1),
                Map.of("name", "Run", "frequency", "DAILY", "goalPeriod", "WEEKLY", "goalTargetCount", 1),
                Map.of("name", "Run", "frequency", "DAILY", "goalPeriod", "WEEKLY", "goalTargetCount", 7),
                Map.of("name", "Review", "frequency", "WEEKLY", "goalPeriod", "WEEKLY", "goalTargetCount", 1));
        for (Map<String, Object> habit : reachable) {
            assertThat(send(HttpMethod.POST, "/api/habits", token, habit).getStatusCode().value()).isEqualTo(201);
        }
    }

    @Test
    void anEditThatWouldMakeTheGoalUnreachableIsRejectedAndChangesNothing() {
        String token = registerUser(uniqueEmail());
        long habitId = send(HttpMethod.POST, "/api/habits", token, Map.of(
                "name", "Run", "frequency", "DAILY", "goalPeriod", "WEEKLY", "goalTargetCount", 3))
                .getBody().get("id").asLong();

        ResponseEntity<JsonNode> response = send(HttpMethod.PUT, "/api/habits/" + habitId, token, Map.of(
                "name", "Run", "frequency", "WEEKLY", "goalPeriod", "WEEKLY", "goalTargetCount", 3));

        assertProblem(response, 400);
        assertThat(jdbc.queryForMap("select frequency, goal_target_count from habit where id = ?", habitId))
                .containsEntry("frequency", "DAILY").containsEntry("goal_target_count", 3);
    }

    @Test
    void changingTheFrequencyWithoutAGoalResetsTheGoal() {
        String token = registerUser(uniqueEmail());
        long habitId = send(HttpMethod.POST, "/api/habits", token, Map.of(
                "name", "Run", "frequency", "DAILY", "goalPeriod", "WEEKLY", "goalTargetCount", 3))
                .getBody().get("id").asLong();

        ResponseEntity<JsonNode> response = send(HttpMethod.PUT, "/api/habits/" + habitId, token,
                Map.of("name", "Run", "frequency", "WEEKLY"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("goalPeriod").asString()).isEqualTo("WEEKLY");
        assertThat(response.getBody().get("goalTargetCount").asInt()).isEqualTo(1);
    }

    @Test
    void aHabitSavedWithAnUnreachableGoalBeforeTheRuleStillLoads() {
        String email = uniqueEmail();
        String token = registerUser(email);
        jdbc.update("insert into habit (user_id, name, description, frequency, goal_period, goal_target_count, "
                + "xp_total, current_streak, longest_streak, created_at) "
                + "values (?, 'Water', '', 'DAILY', 'DAILY', 3, 0, 0, 0, now())", userIdFor(email));

        ResponseEntity<JsonNode> response = send(HttpMethod.GET, "/api/habits", token, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get(0).get("goalTargetCount").asInt()).isEqualTo(3);
    }

    @Test
    void controlCharactersInANameOrDescriptionAreRejected() {
        String email = uniqueEmail();
        String token = registerUser(email);

        // A line feed, a tab, NEL (a C1 control some log viewers break lines on), and LINE SEPARATOR.
        String[] bad = {"Run\n2026-10-05 ERROR forged log line", "Run\tfast", "Run\u0085fast", "fast\u2028slow"};
        for (String text : bad) {
            ResponseEntity<JsonNode> name = send(HttpMethod.POST, "/api/habits", token,
                    Map.of("name", text, "frequency", "DAILY"));
            ResponseEntity<JsonNode> description = send(HttpMethod.POST, "/api/habits", token,
                    Map.of("name", "Run", "description", text, "frequency", "DAILY"));

            assertProblem(name, 400);
            assertThat(name.getBody().get("errors").has("name")).isTrue();
            assertProblem(description, 400);
            assertThat(description.getBody().get("errors").has("description")).isTrue();
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
        assertThat(habit.get("goalPeriod").asString()).isEqualTo("WEEKLY");
        assertThat(habit.get("xpTotal").asInt()).isZero();
        assertThat(habit.get("createdAt").asString()).matches(".*(Z|[+-]\\d{2}:\\d{2})$"); // carries a UTC offset
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
        assertThat(second.getBody().get("detail").asString()).isEqualTo("Email already used");
    }

    @Test
    void loggingInWithTheWrongPasswordIs401() {
        String email = uniqueEmail();
        registerUser(email);

        ResponseEntity<JsonNode> response = send(HttpMethod.POST, "/api/auth/login", null,
                Map.of("email", email, "password", "definitely-the-wrong-password"));

        assertProblem(response, 401);
        assertThat(response.getBody().get("detail").asString()).isEqualTo("Invalid credentials");
    }

    @Test
    void signingInWithAnEmailLongerThanAnyAccountCanHaveIsABadRequest() {
        String tooLong = "a".repeat(244) + "@example.com"; // 256 characters

        ResponseEntity<JsonNode> response = send(HttpMethod.POST, "/api/auth/login", null,
                Map.of("email", tooLong, "password", "any-password"));

        assertProblem(response, 400);
        assertThat(response.getBody().get("errors").has("email")).isTrue();
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
