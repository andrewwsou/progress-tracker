package com.progresstracker.progressworker.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.progresstracker.progressworker.model.WeeklySummary;
import com.progresstracker.progressworker.summary.WeeklySummaryJob;
import com.progresstracker.progressworker.summary.WeeklySummaryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The weekly summary job end to end: real PostgreSQL, the real Claude SDK, and WireMock standing in
 * for the Claude API, so every path (success, timeout, retry, refusal, bad output, budget) runs
 * without an API key or a network.
 */
class WeeklySummaryJobIT extends WorkerIntegrationTestBase {

    private static final WireMockServer CLAUDE = new WireMockServer(options().dynamicPort());

    static {
        CLAUDE.start();
    }

    private static final LocalDate WEEK = LocalDate.of(2026, 9, 21); // a Monday
    private static final String MESSAGES = "/v1/messages";
    private static final JsonMapper JSON = new JsonMapper();

    @DynamicPropertySource
    static void summaryProperties(DynamicPropertyRegistry registry) {
        registry.add("worker.enabled", () -> "false"); // no queue poller in this context
        registry.add("summary.llm.enabled", () -> "true");
        registry.add("summary.llm.api-key", () -> "test-key");
        registry.add("summary.llm.base-url", CLAUDE::baseUrl);
        registry.add("summary.llm.timeout", () -> "3s"); // generous, so a slow CI runner never causes a retry
        registry.add("summary.llm.max-retries", () -> "1");
        registry.add("summary.llm.daily-token-budget", () -> "1000000");
        registry.add("summary.llm.max-calls-per-day", () -> "1000");
    }

    @Autowired
    private WeeklySummaryJob job;

    @Autowired
    private WeeklySummaryStore store;

    @BeforeEach
    void startClean() {
        CLAUDE.resetAll();
        // Nothing left over from an earlier test may be claimed by this one.
        jdbc.update("update weekly_summaries set status = 'FAILED' where status in ('PENDING', 'IN_PROGRESS')");
        jdbc.update("delete from llm_daily_usage");
    }

    @Test
    void claudeWritesTheSummaryAndItsCostIsRecorded() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        long userId = jdbc.queryForObject(
                "insert into app_user (email, password_hash) values (?, 'x') returning id", Long.class, email);
        seedWeek(userId);
        long summaryId = requestSummary(userId);
        stubClaude(okJson(message(output("Read carried your week",
                "You completed Read 3 times and Meditate once for 46 XP. Your longest Read streak is 9 days.",
                "Meditate"), "end_turn", 412, 96)));

        assertThat(job.runOnce()).isEqualTo(1);

        Map<String, Object> row = summary(summaryId);
        assertThat(row.get("status")).isEqualTo("READY");
        assertThat(row.get("source")).isEqualTo("AI");
        assertThat(row.get("headline")).isEqualTo("Read carried your week");
        assertThat(row.get("focus_habit")).isEqualTo("Meditate");
        assertThat(row.get("completions")).isEqualTo(4);
        assertThat(row.get("xp_earned")).isEqualTo(46);
        assertThat(row.get("model")).isEqualTo("claude-opus-5-5");
        assertThat(row.get("input_tokens")).isEqualTo(412);
        assertThat(row.get("output_tokens")).isEqualTo(96);
        assertThat(row.get("latency_ms")).isNotNull();
        assertThat(row.get("fallback_reason")).isNull();
        verify(emailService).queueWeeklySummaryEmail(userId, "Read carried your week");

        // What was sent: structured output at low effort, refusal fallbacks on, and only the
        // habit data. The user's email address never leaves the system.
        CLAUDE.verify(1, postRequestedFor(urlPathEqualTo(MESSAGES))
                .withHeader("x-api-key", equalTo("test-key"))
                .withHeader("anthropic-beta", containing("server-side-fallback-2026-07-01"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("claude-opus-5-5")))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("2048")))
                .withRequestBody(matchingJsonPath("$.output_config.effort", equalTo("low")))
                .withRequestBody(matchingJsonPath("$.output_config.format.type", equalTo("json_schema")))
                .withRequestBody(matchingJsonPath("$.fallbacks", equalTo("default")))
                .withRequestBody(matchingJsonPath("$.system", containing("weekly summary")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("Meditate")))
                .withRequestBody(notContaining(email)));
    }

    @Test
    void aSlowAnswerTimesOutIsRetriedOnceAndThenTheTemplateWritesTheSummary() {
        long summaryId = requestSummary(userWithSeededWeek());
        stubClaude(aResponse().withFixedDelay(5_000).withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(message(output("Late", "Too late.", "Read"), "end_turn", 1, 1)));

        job.runOnce();

        Map<String, Object> row = summary(summaryId);
        assertThat(row.get("status")).isEqualTo("READY");
        assertThat(row.get("source")).isEqualTo("TEMPLATE");
        assertThat(row.get("fallback_reason")).isEqualTo("TIMEOUT_OR_NETWORK");
        assertThat(row.get("headline")).isEqualTo("4 completions and 46 XP this week");
        assertThat(row.get("focus_habit")).isEqualTo("Meditate");
        CLAUDE.verify(2, postRequestedFor(urlPathEqualTo(MESSAGES))); // the first attempt and one retry
        // Both requests were counted against today's cap before they were sent, although the
        // timed-out answers reported no usage.
        assertThat(reservedCallsToday()).isEqualTo(2);
    }

    @Test
    void aServerErrorIsRetriedAndTheRetryWritesTheSummary() {
        long summaryId = requestSummary(userWithSeededWeek());
        CLAUDE.stubFor(post(urlPathEqualTo(MESSAGES)).inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500).withBody("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"oops\"}}"))
                .willSetStateTo("recovered"));
        CLAUDE.stubFor(post(urlPathEqualTo(MESSAGES)).inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(okJson(message(output("Read carried your week", "Read 3 times.", "Meditate"), "end_turn", 400, 90))));

        job.runOnce();

        assertThat(summary(summaryId).get("source")).isEqualTo("AI");
        CLAUDE.verify(2, postRequestedFor(urlPathEqualTo(MESSAGES)));
    }

    @Test
    void persistentServerErrorsFallBackToTheTemplate() {
        long summaryId = requestSummary(userWithSeededWeek());
        stubClaude(aResponse().withStatus(500)
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"oops\"}}"));

        job.runOnce();

        Map<String, Object> row = summary(summaryId);
        assertThat(row.get("source")).isEqualTo("TEMPLATE");
        assertThat(row.get("fallback_reason")).isEqualTo("SERVER_ERROR_500");
    }

    @Test
    void aRefusalFallsBackToTheTemplateAndItsTokensStillCount() {
        long summaryId = requestSummary(userWithSeededWeek());
        stubClaude(okJson(message(null, "refusal", 300, 0)));

        job.runOnce();

        Map<String, Object> row = summary(summaryId);
        assertThat(row.get("source")).isEqualTo("TEMPLATE");
        assertThat(row.get("fallback_reason")).isEqualTo("REFUSED");
        assertThat(row.get("input_tokens")).isEqualTo(300);
    }

    @Test
    void aHabitTheModelInventedIsCaughtBeforeAnyoneSeesIt() {
        long summaryId = requestSummary(userWithSeededWeek());
        stubClaude(okJson(message(output("Great week", "Swimming is going well.", "Swimming"), "end_turn", 400, 90)));

        job.runOnce();

        Map<String, Object> row = summary(summaryId);
        assertThat(row.get("source")).isEqualTo("TEMPLATE");
        assertThat(row.get("fallback_reason")).isEqualTo("VALIDATION: focusHabit is not one of the user's habits");
        assertThat(row.get("model")).isEqualTo("claude-opus-5-5"); // the call happened, so its cost is kept
        assertThat(row.get("output_tokens")).isEqualTo(90);
    }

    @Test
    void aNumberTheModelInventedIsCaughtBeforeAnyoneSeesIt() {
        long summaryId = requestSummary(userWithSeededWeek());
        stubClaude(okJson(message(output("Great week", "You completed 12 habits.", "Read"), "end_turn", 400, 90)));

        job.runOnce();

        assertThat(summary(summaryId).get("fallback_reason"))
                .isEqualTo("VALIDATION: mentions 12, which is not in the data");
    }

    @Test
    void onceTodaysTokenBudgetIsSpentTheTemplateWritesWithoutCallingClaude() {
        long spender = insertUser();
        long budgetRow = jdbc.queryForObject("""
                insert into weekly_summaries (user_id, week_start, status, attempts, created_at, completed_at,
                                              source, input_tokens, output_tokens)
                values (?, ?, 'READY', 1, now(), now(), 'AI', 999000, 1000) returning id
                """, Long.class, spender, WEEK.minusWeeks(52));
        try {
            long summaryId = requestSummary(userWithSeededWeek());

            job.runOnce();

            assertThat(summary(summaryId).get("fallback_reason")).isEqualTo("DAILY_TOKEN_BUDGET_SPENT");
            CLAUDE.verify(0, postRequestedFor(urlPathEqualTo(MESSAGES)));
        } finally {
            jdbc.update("delete from weekly_summaries where id = ?", budgetRow);
        }
    }

    @Test
    void theDailyCallCapHoldsWhenTwoWorkersRace() throws Exception {
        // Room for 4 more requests today. Each summary reserves 2 (one attempt plus one retry).
        jdbc.update("insert into llm_daily_usage (usage_date, calls, reserved_tokens) "
                + "values ((now() at time zone 'utc')::date, 996, 0)");
        List<Long> summaryIds = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            summaryIds.add(requestSummary(userWithSeededWeek()));
        }
        stubClaude(okJson(message(output("Read carried your week", "Read 3 times.", "Meditate"), "end_turn", 400, 90)));

        List<Callable<?>> workers = List.of(job::runOnce, job::runOnce);
        assertThat(runAtOnce(workers)).containsOnlyNulls();

        List<Object> reasons = summaryIds.stream().map(id -> summary(id).get("fallback_reason")).toList();
        assertThat(reasons).filteredOn(java.util.Objects::isNull).hasSize(2); // written by Claude
        assertThat(reasons).filteredOn("DAILY_LLM_CAP_REACHED"::equals).hasSize(4);
        CLAUDE.verify(2, postRequestedFor(urlPathEqualTo(MESSAGES)));
        assertThat(reservedCallsToday()).isEqualTo(1000);
    }

    @Test
    void aWeekWithTooManyHabitsIsNeverSent() {
        long userId = insertUser();
        for (int i = 0; i < 21; i++) {
            insertEntry(insertHabit(userId, "Habit " + i, 1, 1), WEEK, 10);
        }
        long summaryId = requestSummary(userId);

        job.runOnce();

        assertThat(summary(summaryId).get("fallback_reason")).isEqualTo("TOO_MANY_HABITS");
        CLAUDE.verify(0, postRequestedFor(urlPathEqualTo(MESSAGES)));
        assertThat(reservedCallsToday()).isZero();
    }

    @Test
    void aClaimWhoseWorkerDiedIsPickedUpAgainAfterItsLeaseExpires() {
        long summaryId = requestSummary(userWithSeededWeek());
        jdbc.update("update weekly_summaries set status = 'IN_PROGRESS', attempts = 1, "
                + "lease_until = now() - interval '1 minute' where id = ?", summaryId);
        stubClaude(okJson(message(output("Read carried your week", "Read 3 times.", "Meditate"), "end_turn", 400, 90)));

        assertThat(job.runOnce()).isEqualTo(1);

        Map<String, Object> row = summary(summaryId);
        assertThat(row.get("status")).isEqualTo("READY");
        assertThat(row.get("attempts")).isEqualTo(2);
    }

    @Test
    void aSummaryIsGivenUpOnAfterItsLastAttempt() {
        long summaryId = requestSummary(userWithSeededWeek());
        jdbc.update("update weekly_summaries set status = 'IN_PROGRESS', attempts = 3, "
                + "lease_until = now() - interval '1 minute' where id = ?", summaryId);

        assertThat(job.runOnce()).isZero();

        Map<String, Object> row = summary(summaryId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("last_error")).isEqualTo("Gave up after 3 attempts");
        CLAUDE.verify(0, postRequestedFor(urlPathEqualTo(MESSAGES)));
    }

    @Test
    void aWaitingSummaryOverALoweredAttemptLimitIsGivenUpOn() {
        long summaryId = requestSummary(userWithSeededWeek());
        jdbc.update("update weekly_summaries set attempts = 3 where id = ?", summaryId);

        assertThat(job.runOnce()).isZero();

        assertThat(summary(summaryId).get("status")).isEqualTo("FAILED");
    }

    @Test
    void aClaimIsRenewedBeforeItsSummaryIsWrittenButNotOnceAnotherWorkerHasIt() {
        long userId = userWithSeededWeek();
        long summaryId = requestSummary(userId);
        jdbc.update("update weekly_summaries set status = 'IN_PROGRESS', attempts = 2, "
                + "lease_until = now() + interval '1 second' where id = ?", summaryId);

        assertThat(store.renew(new WeeklySummaryStore.Claim(summaryId, userId, WEEK, 1), Duration.ofMinutes(5))).isFalse();
        assertThat(store.renew(new WeeklySummaryStore.Claim(summaryId, userId, WEEK, 2), Duration.ofMinutes(5))).isTrue();

        assertThat(jdbc.queryForObject("select lease_until > now() + interval '4 minutes' from weekly_summaries where id = ?",
                Boolean.class, summaryId)).isTrue();
    }

    @Test
    void aBacklogLargerThanOneBatchIsWorkedThroughInOneRun() {
        List<Long> summaryIds = new ArrayList<>();
        for (int i = 0; i < 7; i++) { // the batch size is 5
            summaryIds.add(requestSummary(userWithSeededWeek()));
        }
        stubClaude(okJson(message(output("Read carried your week", "Read 3 times.", "Meditate"), "end_turn", 400, 90)));

        job.runOnSchedule();

        for (long summaryId : summaryIds) {
            assertThat(summary(summaryId).get("status")).isEqualTo("READY");
        }
    }

    @Test
    void aHabitWithNoNameFromOlderDataStillGetsASummary() {
        long userId = userWithSeededWeek();
        long unnamed = jdbc.queryForObject("""
                insert into habit (user_id, name, description, frequency, goal_period, goal_target_count,
                                   xp_total, current_streak, longest_streak, created_at)
                values (?, null, '', 'DAILY', 'DAILY', 1, 0, 0, 0, now()) returning id
                """, Long.class, userId);
        insertEntry(unnamed, WEEK.plusDays(3), 10);
        long summaryId = requestSummary(userId);
        stubClaude(okJson(message(output("Read carried your week", "Read 3 times.", "Unnamed habit"), "end_turn", 400, 90)));

        job.runOnce();

        Map<String, Object> row = summary(summaryId);
        assertThat(row.get("status")).isEqualTo("READY");
        assertThat(row.get("source")).isEqualTo("AI");
        assertThat(row.get("completions")).isEqualTo(5);
    }

    @Test
    void whenAFallbackModelAnswersTheTokensOfEveryAttemptAreCounted() {
        long summaryId = requestSummary(userWithSeededWeek());
        // The first model declined, a fallback model answered. Top-level usage covers only the answer.
        String iterations = ",\"iterations\":["
                + "{\"type\":\"message\",\"input_tokens\":400,\"output_tokens\":20,"
                + "\"cache_creation\":null,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0},"
                + "{\"type\":\"fallback_message\",\"model\":\"claude-opus-5\",\"input_tokens\":410,\"output_tokens\":90,"
                + "\"cache_creation\":null,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}]";
        String body = message(output("Read carried your week", "Read 3 times.", "Meditate"), "end_turn", 410, 90)
                .replace("\"output_tokens\":90}", "\"output_tokens\":90" + iterations + "}");
        stubClaude(okJson(body));

        job.runOnce();

        Map<String, Object> row = summary(summaryId);
        assertThat(row.get("source")).isEqualTo("AI");
        assertThat(row.get("input_tokens")).isEqualTo(810);
        assertThat(row.get("output_tokens")).isEqualTo(110);
    }

    @Test
    void aWorkerWhoseClaimWasTakenOverCannotOverwriteTheNewOne() {
        long userId = userWithSeededWeek();
        long summaryId = requestSummary(userId);
        // Another worker took the row over after this one's lease expired: it is on attempt 2.
        jdbc.update("update weekly_summaries set status = 'IN_PROGRESS', attempts = 2 where id = ?", summaryId);
        WeeklySummaryStore.Claim staleClaim = new WeeklySummaryStore.Claim(summaryId, userId, WEEK, 1);

        boolean written = store.finish(staleClaim, new WeeklySummary.Result(4, 46, "Stale", "Stale copy.", "Read",
                WeeklySummary.Source.AI, null, "claude-opus-5-5", 1, 1, 1));

        assertThat(written).isFalse();
        Map<String, Object> row = summary(summaryId);
        assertThat(row.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(row.get("headline")).isNull();
    }

    @Test
    void twoWorkersRunningAtOnceWriteEachSummaryExactlyOnce() throws Exception {
        List<Long> summaryIds = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            summaryIds.add(requestSummary(userWithSeededWeek()));
        }
        // Slow answers keep both workers busy at the same time.
        stubClaude(aResponse().withFixedDelay(300).withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(message(output("Read carried your week", "Read 3 times.", "Meditate"), "end_turn", 400, 90)));

        List<Callable<?>> workers = List.of(job::runOnce, job::runOnce);
        assertThat(runAtOnce(workers)).containsOnlyNulls();

        CLAUDE.verify(6, postRequestedFor(urlPathEqualTo(MESSAGES)));
        for (long summaryId : summaryIds) {
            Map<String, Object> row = summary(summaryId);
            assertThat(row.get("status")).isEqualTo("READY");
            assertThat(row.get("source")).isEqualTo("AI");
            assertThat(row.get("attempts")).isEqualTo(1);
        }
        // Each summary was emailed once, not once per worker.
        verify(emailService, times(6)).queueWeeklySummaryEmail(anyLong(), eq("Read carried your week"));
    }

    // --- helpers ---------------------------------------------------------------------------

    /** A new user with two habits: Read (3 completions, 36 XP) and Meditate (1 completion, 10 XP). */
    private long userWithSeededWeek() {
        long userId = insertUser();
        seedWeek(userId);
        return userId;
    }

    private void seedWeek(long userId) {
        long read = insertHabit(userId, "Read", 3, 9);
        long meditate = insertHabit(userId, "Meditate", 1, 4);
        insertEntry(read, WEEK, 10);
        insertEntry(read, WEEK.plusDays(1), 12);
        insertEntry(read, WEEK.plusDays(2), 14);
        insertEntry(meditate, WEEK.plusDays(4), 10);
        insertEntry(read, WEEK.minusDays(1), 10); // the week before: not part of this summary
    }

    private long insertHabit(long userId, String name, int currentStreak, int longestStreak) {
        return jdbc.queryForObject("""
                insert into habit (user_id, name, description, frequency, goal_period, goal_target_count,
                                   xp_total, current_streak, longest_streak, created_at)
                values (?, ?, '', 'DAILY', 'DAILY', 1, 0, ?, ?, now()) returning id
                """, Long.class, userId, name, currentStreak, longestStreak);
    }

    /** What the API does when the weekly job runs: one PENDING row per user. */
    private long requestSummary(long userId) {
        return jdbc.queryForObject("""
                insert into weekly_summaries (user_id, week_start, status, attempts, created_at)
                values (?, ?, 'PENDING', 0, now()) returning id
                """, Long.class, userId, WEEK);
    }

    private int reservedCallsToday() {
        return jdbc.queryForObject("select coalesce(sum(calls), 0) from llm_daily_usage", Integer.class);
    }

    private Map<String, Object> summary(long id) {
        return jdbc.queryForMap("select * from weekly_summaries where id = ?", id);
    }

    private static void stubClaude(com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
        MappingBuilder request = post(urlPathEqualTo(MESSAGES));
        CLAUDE.stubFor(request.willReturn(response));
    }

    private static String output(String headline, String body, String focusHabit) {
        try {
            return JSON.writeValueAsString(Map.of("headline", headline, "body", body, "focusHabit", focusHabit));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A Messages API response. Thinking is always on for this model, so a real response starts with
     * a thinking block (empty by default) before the text.
     */
    private static String message(String text, String stopReason, int inputTokens, int outputTokens) {
        try {
            String content = text == null ? "[]" : "[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig\"},"
                    + "{\"type\":\"text\",\"text\":" + JSON.writeValueAsString(text) + "}]";
            return "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-5-5\","
                    + "\"content\":" + content + ",\"stop_reason\":\"" + stopReason + "\",\"stop_sequence\":null,"
                    + "\"usage\":{\"input_tokens\":" + inputTokens + ",\"output_tokens\":" + outputTokens + "}}";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
