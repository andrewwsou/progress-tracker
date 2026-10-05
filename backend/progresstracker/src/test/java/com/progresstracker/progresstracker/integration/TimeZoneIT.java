package com.progresstracker.progresstracker.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Today" is the user's day. The clock is fixed at 02:30 UTC on Monday 5 October 2026, which is
 * still Sunday evening, 4 October, in Los Angeles.
 */
@TestPropertySource(properties = "queue.enabled=false")
@Import(TimeZoneIT.FixedClock.class)
class TimeZoneIT extends IntegrationTestBase {

    static final Instant NOW = Instant.parse("2026-10-05T02:30:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Test
    void aCompletionCountsForTheUsersOwnDay() {
        String losAngeles = register("America/Los_Angeles");
        String utc = register(null);

        long laHabit = createDailyHabit(losAngeles, "Read");
        long utcHabit = createDailyHabit(utc, "Read");
        ResponseEntity<JsonNode> laCompletion = completeHabit(losAngeles, laHabit);
        completeHabit(utc, utcHabit);

        assertThat(completedDate(laHabit)).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(completedDate(utcHabit)).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(laCompletion.getBody().get("completedForPeriod").asBoolean()).isTrue();
        assertThat(laCompletion.getBody().get("lastCompletedDate").asString()).isEqualTo("2026-10-04");
    }

    @Test
    void movingToAnotherTimeZoneMovesTheUsersDay() {
        String token = register("America/Los_Angeles");
        long habit = createDailyHabit(token, "Read");
        completeHabit(token, habit); // done for 4 October, Los Angeles time

        ResponseEntity<JsonNode> moved = send(HttpMethod.PUT, "/api/me", token, Map.of("timeZone", "Pacific/Kiritimati"));

        assertThat(moved.getStatusCode().value()).isEqualTo(200);
        assertThat(send(HttpMethod.GET, "/api/me", token, null).getBody().get("timeZone").asString())
                .isEqualTo("Pacific/Kiritimati");
        // On Kiritimati (UTC+14) it is already the afternoon of 5 October: not done for that day yet.
        JsonNode habits = send(HttpMethod.GET, "/api/habits", token, null).getBody();
        assertThat(habits.get(0).get("completedForPeriod").asBoolean()).isFalse();
    }

    @Test
    void anUnusableZoneNeverBlocksSignUpButIsRefusedAsAChange() {
        // Sign-up still works; the account just keeps UTC.
        String token = register("Mars/Olympus_Mons");
        assertThat(send(HttpMethod.GET, "/api/me", token, null).getBody().get("timeZone").asString()).isEqualTo("UTC");

        // Changing it is strict: fixed offsets, abbreviations PostgreSQL reads as fixed offsets, and
        // ids PostgreSQL does not know at all are refused.
        for (String zone : java.util.List.of("+05:00", "CET", "SystemV/PST8", "Mars/Olympus_Mons")) {
            ResponseEntity<JsonNode> update = send(HttpMethod.PUT, "/api/me", token, Map.of("timeZone", zone));
            assertThat(update.getStatusCode().value()).as(zone).isEqualTo(400);
        }
    }

    @Test
    void aCompletionDatedAfterTheNewTodayCountsAsDoneAfterMovingWest() {
        String token = register("Pacific/Kiritimati"); // UTC+14: already 5 October afternoon
        long habit = createDailyHabit(token, "Read");
        completeHabit(token, habit);                      // dated 5 October
        send(HttpMethod.PUT, "/api/me", token, Map.of("timeZone", "America/Los_Angeles")); // still 4 October

        ResponseEntity<JsonNode> again = completeHabit(token, habit);

        // Done already: no second entry for the 4th, and the streak does not move backwards.
        assertThat(again.getBody().get("completedForPeriod").asBoolean()).isTrue();
        assertThat(again.getBody().get("lastCompletedDate").asString()).isEqualTo("2026-10-05");
        assertThat(jdbc.queryForObject("select count(*) from habit_entries where habit_id = ?", Integer.class, habit))
                .isEqualTo(1);
    }

    private String register(String timeZone) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("email", uniqueEmail(), "password", "correct-horse-battery-staple"));
        if (timeZone != null) {
            body.put("timeZone", timeZone);
        }
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/auth/register", body, JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody().get("token").asString();
    }

    private LocalDate completedDate(long habitId) {
        return jdbc.queryForObject("select completed_date from habit_entries where habit_id = ?", LocalDate.class, habitId);
    }
}
