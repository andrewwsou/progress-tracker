package com.progresstracker.progresstracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may do what. Each test logs in real users through the API and checks both the response
 * and what actually ended up in the database.
 */
@TestPropertySource(properties = "queue.enabled=false")
class HabitAccessControlIT extends IntegrationTestBase {

    private static final Map<String, Object> VALID_HABIT =
            Map.of("name", "Renamed", "description", "", "frequency", "DAILY");

    @Test
    void creatingAHabitCannotTakeOverAnotherUsersHabitOrSetItsOwnXp() {
        String victimEmail = uniqueEmail();
        long victimsHabit = createDailyHabit(registerUser(victimEmail), "Victim's habit");
        String attackerToken = registerUser(uniqueEmail());

        // Mass assignment attempt: fields the client must never control.
        ResponseEntity<JsonNode> response = send(HttpMethod.POST, "/api/habits", attackerToken, Map.of(
                "id", victimsHabit,
                "name", "Mine now",
                "description", "",
                "frequency", "DAILY",
                "xpTotal", 9999,
                "currentStreak", 50));

        // The victim's habit is untouched...
        Map<String, Object> victimRow = jdbc.queryForMap(
                "select user_id, name, xp_total from habit where id = ?", victimsHabit);
        assertThat(victimRow.get("user_id")).isEqualTo(userIdFor(victimEmail));
        assertThat(victimRow.get("name")).isEqualTo("Victim's habit");
        assertThat(victimRow.get("xp_total")).isEqualTo(0);

        // ...and the attacker just gets an ordinary new habit with no head start.
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        long createdId = response.getBody().get("id").asLong();
        assertThat(createdId).isNotEqualTo(victimsHabit);
        Map<String, Object> createdRow = jdbc.queryForMap(
                "select xp_total, current_streak from habit where id = ?", createdId);
        assertThat(createdRow.get("xp_total")).isEqualTo(0);
        assertThat(createdRow.get("current_streak")).isEqualTo(0);
    }

    @Test
    void editingAHabitCannotChangeItsXpOrStreak() {
        String token = registerUser(uniqueEmail());
        long habitId = createDailyHabit(token, "Read");

        ResponseEntity<JsonNode> response = send(HttpMethod.PUT, "/api/habits/" + habitId, token, Map.of(
                "name", "Read more",
                "description", "",
                "frequency", "DAILY",
                "xpTotal", 9999,
                "longestStreak", 365));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> row = jdbc.queryForMap(
                "select name, xp_total, longest_streak from habit where id = ?", habitId);
        assertThat(row.get("name")).isEqualTo("Read more");
        assertThat(row.get("xp_total")).isEqualTo(0);
        assertThat(row.get("longest_streak")).isEqualTo(0);
    }

    @Test
    void aUserOnlySeesTheirOwnHabits() {
        String aliceToken = registerUser(uniqueEmail());
        String bobToken = registerUser(uniqueEmail());
        long alicesHabit = createDailyHabit(aliceToken, "Alice's habit");
        long bobsHabit = createDailyHabit(bobToken, "Bob's habit");

        List<Long> visibleToAlice = new ArrayList<>();
        send(HttpMethod.GET, "/api/habits", aliceToken, null).getBody()
                .forEach(habit -> visibleToAlice.add(habit.get("id").asLong()));

        assertThat(visibleToAlice).containsExactly(alicesHabit).doesNotContain(bobsHabit);
    }

    @Test
    void aUserCannotEditCompleteOrDeleteAnotherUsersHabit() {
        String ownerToken = registerUser(uniqueEmail());
        String intruderToken = registerUser(uniqueEmail());
        long ownersHabit = createDailyHabit(ownerToken, "Journal");
        // The intruder is a real, logged-in user who can complete their own habit...
        long intrudersHabit = createDailyHabit(intruderToken, "Journal");
        assertThat(completeHabit(intruderToken, intrudersHabit).getStatusCode().value()).isEqualTo(200);

        // ...so each rejection below is the ownership check, not a failed login.
        String path = "/api/habits/" + ownersHabit;
        assertThat(send(HttpMethod.PUT, path, intruderToken, VALID_HABIT).getStatusCode().value()).isEqualTo(403);
        assertThat(completeHabit(intruderToken, ownersHabit).getStatusCode().value()).isEqualTo(403);
        assertThat(send(HttpMethod.DELETE, path, intruderToken, null).getStatusCode().value()).isEqualTo(403);

        assertThat(jdbc.queryForObject("select name from habit where id = ?", String.class, ownersHabit))
                .isEqualTo("Journal");
        assertThat(entryCount(ownersHabit)).isZero();
    }

    @Test
    void theOwnerCanDeleteTheirHabit() {
        String token = registerUser(uniqueEmail());
        long habitId = createDailyHabit(token, "Temporary");
        completeHabit(token, habitId);

        assertThat(send(HttpMethod.DELETE, "/api/habits/" + habitId, token, null).getStatusCode().value())
                .isEqualTo(204);
        assertThat(jdbc.queryForObject("select count(*) from habit where id = ?", Integer.class, habitId)).isZero();
        assertThat(entryCount(habitId)).isZero();
    }

    @Test
    void requestsWithoutAValidTokenGet401AndNeverReachTheHabit() {
        long habitId = createDailyHabit(registerUser(uniqueEmail()), "Walk");
        String path = "/api/habits/" + habitId + "/complete";

        for (String token : new String[]{null, "not-a-real-token"}) {
            ResponseEntity<JsonNode> response = send(HttpMethod.POST, path, token, null);

            assertThat(response.getStatusCode().value()).isEqualTo(401);
            assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(response.getBody().get("status").asInt()).isEqualTo(401);
        }
        assertThat(entryCount(habitId)).isZero();
    }
}
