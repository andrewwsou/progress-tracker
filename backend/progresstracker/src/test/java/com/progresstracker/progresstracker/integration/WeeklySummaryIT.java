package com.progresstracker.progresstracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This service's half of weekly summaries: the scheduled job asks for them, and users read the
 * finished ones. Writing them is the worker's job and is covered by its own tests.
 *
 * Requests use a week in 2019 that no other test touches, so counts can be exact.
 */
@TestPropertySource(properties = "queue.enabled=false")
class WeeklySummaryIT extends IntegrationTestBase {

    private static final LocalDate MONDAY_2019 = LocalDate.of(2019, 3, 4);

    @Test
    void theWeeklyJobNeedsTheInternalToken() {
        assertThat(requestSummaries(null, MONDAY_2019).getStatusCode().value()).isEqualTo(401);
        assertThat(requestSummaries("wrong-token", MONDAY_2019).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void asksOnceForEachUserWhoCompletedSomethingThatWeek() {
        String activeEmail = uniqueEmail();
        long activeHabit = createDailyHabit(registerUser(activeEmail), "Read");
        String earlierEmail = uniqueEmail();
        long earlierHabit = createDailyHabit(registerUser(earlierEmail), "Read");
        String idleEmail = uniqueEmail();
        registerUser(idleEmail);
        insertEntry(activeHabit, MONDAY_2019.plusDays(2));   // Wednesday of the week
        insertEntry(earlierHabit, MONDAY_2019.minusDays(4)); // the week before

        // Any day of the week works; it is turned into that week's Monday.
        ResponseEntity<JsonNode> first = requestSummaries(AUTOMATION_TOKEN, MONDAY_2019.plusDays(2));

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(first.getBody().get("weekStart").asText()).isEqualTo("2019-03-04");
        assertThat(first.getBody().get("requestedCount").asInt()).isEqualTo(1);
        assertThat(summaryStatus(activeEmail)).isEqualTo("PENDING");
        assertThat(summaryCount(earlierEmail)).isZero();
        assertThat(summaryCount(idleEmail)).isZero();

        // The scheduler can fire twice for the same week; the second run adds nothing.
        ResponseEntity<JsonNode> again = requestSummaries(AUTOMATION_TOKEN, MONDAY_2019);

        assertThat(again.getBody().get("requestedCount").asInt()).isZero();
        assertThat(summaryCount(activeEmail)).isEqualTo(1);
    }

    @Test
    void askingAgainQueuesASummaryTheWorkerGaveUpOn() {
        LocalDate week = MONDAY_2019.plusWeeks(20);
        String email = uniqueEmail();
        long habit = createDailyHabit(registerUser(email), "Read");
        insertEntry(habit, week);
        requestSummaries(AUTOMATION_TOKEN, week);
        jdbc.update("update weekly_summaries set status = 'FAILED', attempts = 3, last_error = 'Gave up' "
                + "where user_id = ? and week_start = ?", userIdFor(email), week);

        ResponseEntity<JsonNode> again = requestSummaries(AUTOMATION_TOKEN, week);

        assertThat(again.getBody().get("requestedCount").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForMap("select status, attempts, last_error from weekly_summaries "
                + "where user_id = ? and week_start = ?", userIdFor(email), week))
                .containsEntry("status", "PENDING").containsEntry("attempts", 0).containsEntry("last_error", null);
    }

    @Test
    void withoutAWeekItSummarizesLastWeek() {
        LocalDate lastMonday = LocalDate.now().minusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        ResponseEntity<JsonNode> response = requestSummaries(AUTOMATION_TOKEN, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("weekStart").asText()).isEqualTo(lastMonday.toString());
    }

    @Test
    void theLatestSummaryIsTheCallersNewestFinishedOne() {
        String email = uniqueEmail();
        String token = registerUser(email);
        long userId = userIdFor(email);

        // Nothing written yet.
        assertThat(send(HttpMethod.GET, "/api/summaries/latest", token, null).getStatusCode().value()).isEqualTo(204);

        insertSummary(userId, MONDAY_2019, "READY", "An older week");
        insertSummary(userId, MONDAY_2019.plusWeeks(1), "READY", "The newest finished week");
        insertSummary(userId, MONDAY_2019.plusWeeks(2), "PENDING", null); // not written yet
        long someoneElse = userIdFor(registerUserReturningEmail());
        insertSummary(someoneElse, MONDAY_2019.plusWeeks(3), "READY", "Someone else's week");

        ResponseEntity<JsonNode> response = send(HttpMethod.GET, "/api/summaries/latest", token, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode summary = response.getBody();
        assertThat(summary.get("headline").asText()).isEqualTo("The newest finished week");
        assertThat(summary.get("weekStart").asText()).isEqualTo("2019-03-11");
        assertThat(summary.get("weekEnd").asText()).isEqualTo("2019-03-17");
        assertThat(summary.get("completions").asInt()).isEqualTo(4);
        assertThat(summary.get("xpEarned").asInt()).isEqualTo(46);
        assertThat(summary.get("focusHabit").asText()).isEqualTo("Meditate");
        assertThat(summary.get("source").asText()).isEqualTo("AI");
        assertThat(summary.get("writtenAt").asText()).matches(".*(Z|[+-]\\d{2}:\\d{2})$");
        assertThat(summary.has("user")).isFalse();
    }

    @Test
    void readingASummaryNeedsALogin() {
        assertThat(send(HttpMethod.GET, "/api/summaries/latest", null, null).getStatusCode().value()).isEqualTo(401);
    }

    // --- helpers ---------------------------------------------------------------------------

    private ResponseEntity<JsonNode> requestSummaries(String internalToken, LocalDate weekStart) {
        HttpHeaders headers = new HttpHeaders();
        if (internalToken != null) {
            headers.set("X-Internal-Token", internalToken);
        }
        String path = "/api/internal/automations/weekly-summary" + (weekStart == null ? "" : "?weekStart=" + weekStart);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(headers), JsonNode.class);
    }

    private String registerUserReturningEmail() {
        String email = uniqueEmail();
        registerUser(email);
        return email;
    }

    private void insertEntry(long habitId, LocalDate date) {
        jdbc.update("insert into habit_entries (habit_id, completed_date, xp_earned, created_at) values (?, ?, 10, now())",
                habitId, date);
    }

    private void insertSummary(long userId, LocalDate weekStart, String status, String headline) {
        boolean ready = "READY".equals(status);
        jdbc.update("""
                insert into weekly_summaries (user_id, week_start, status, attempts, created_at, completed_at,
                                              completions, xp_earned, headline, body, focus_habit, source)
                values (?, ?, ?, 1, now(), ?, ?, ?, ?, ?, ?, ?)
                """,
                userId, weekStart, status,
                ready ? java.time.OffsetDateTime.now() : null,
                ready ? 4 : null, ready ? 46 : null,
                headline, ready ? "Body text." : null, ready ? "Meditate" : null, ready ? "AI" : null);
    }

    private int summaryCount(String email) {
        return jdbc.queryForObject(
                "select count(*) from weekly_summaries where user_id = ? and week_start = ?",
                Integer.class, userIdFor(email), MONDAY_2019);
    }

    private String summaryStatus(String email) {
        return jdbc.queryForObject(
                "select status from weekly_summaries where user_id = ? and week_start = ?",
                String.class, userIdFor(email), MONDAY_2019);
    }
}
