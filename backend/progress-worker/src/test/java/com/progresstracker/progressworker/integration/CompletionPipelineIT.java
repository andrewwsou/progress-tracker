package com.progresstracker.progressworker.integration;

import com.progresstracker.progressworker.service.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Runs the real worker (poller thread, AWS SDK client, JPA) against PostgreSQL and an
 * SQS-compatible broker in Docker, and checks what it does to the database for each kind
 * of message the queue can hand it: a normal event, duplicates, garbage, and failures.
 */
@SpringBootTest
class CompletionPipelineIT {

    /** Same value as the production queue's redrive policy in infra/terraform/sqs.tf. */
    private static final int MAX_RECEIVE_COUNT = 5;

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    private static final String DEAD_LETTER_QUEUE_URL;
    private static final String QUEUE_URL;

    static {
        POSTGRES.start();
        String suffix = UUID.randomUUID().toString();
        DEAD_LETTER_QUEUE_URL = LocalSqs.createQueue("completions-dlq-" + suffix);
        QUEUE_URL = LocalSqs.createQueueWithDeadLetter("completions-" + suffix, DEAD_LETTER_QUEUE_URL, MAX_RECEIVE_COUNT);
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("queue.enabled", () -> "true");
        registry.add("queue.sqsUrl", () -> QUEUE_URL);
        registry.add("queue.endpointOverride", LocalSqs::endpoint);

        // Short poll and visibility timeout so a failed message is redelivered in seconds.
        registry.add("worker.waitTimeSeconds", () -> "1");
        registry.add("worker.visibilityTimeoutSeconds", () -> "2");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private EmailService emailService;

    @Test
    void grantsXpStreakAndFirstAchievementForACompletionEvent() {
        long userId = insertUser();
        long habitId = insertHabit(userId);
        LocalDate today = LocalDate.now();
        insertEntry(habitId, today, 0); // what the API writes before it publishes the event

        LocalSqs.send(QUEUE_URL, event(userId, habitId, today));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(xpTotal(habitId)).isEqualTo(10));
        assertThat(currentStreak(habitId)).isEqualTo(1);
        assertThat(xpEarned(habitId, today)).isEqualTo(10);
        assertThat(unlockedAchievements(userId)).containsExactly("FIRST_COMPLETION");
    }

    @Test
    void extendsTheStreakAndPaysTheStreakBonusWhenYesterdayWasCompleted() {
        long userId = insertUser();
        long habitId = insertHabit(userId);
        LocalDate today = LocalDate.now();
        insertEntry(habitId, today.minusDays(1), 10);
        jdbc.update("update habit set xp_total = 10, current_streak = 1, longest_streak = 1, last_completed_date = ? where id = ?",
                today.minusDays(1), habitId);
        insertEntry(habitId, today, 0);

        LocalSqs.send(QUEUE_URL, event(userId, habitId, today));

        // Day two of a streak earns 10 base + 2 bonus.
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(xpEarned(habitId, today)).isEqualTo(12));
        assertThat(xpTotal(habitId)).isEqualTo(22);
        assertThat(currentStreak(habitId)).isEqualTo(2);
    }

    @Test
    void duplicateDeliveriesOfOneEventGrantTheRewardOnlyOnce() {
        long userId = insertUser();
        long habitId = insertHabit(userId);
        LocalDate today = LocalDate.now();
        insertEntry(habitId, today, 0);
        String event = event(userId, habitId, today);

        // SQS is at-least-once: the same event can arrive more than once.
        LocalSqs.send(QUEUE_URL, event);
        LocalSqs.send(QUEUE_URL, event);
        LocalSqs.send(QUEUE_URL, event);

        awaitQueueDrained();
        assertThat(xpTotal(habitId)).isEqualTo(10);
        assertThat(entryCount(habitId)).isEqualTo(1);
        assertThat(unlockedAchievements(userId)).containsExactly("FIRST_COMPLETION");
    }

    @Test
    void malformedMessagesAreDeletedAndDoNotBlockTheQueue() {
        List<String> poison = List.of(
                "this is not json",
                "{\"habitId\": 1}",
                "{\"userId\": 1, \"habitId\": 1, \"date\": \"not-a-date\"}");
        poison.forEach(body -> LocalSqs.send(QUEUE_URL, body));

        long userId = insertUser();
        long habitId = insertHabit(userId);
        LocalDate today = LocalDate.now();
        insertEntry(habitId, today, 0);
        LocalSqs.send(QUEUE_URL, event(userId, habitId, today));

        // The valid event behind the bad ones is still processed...
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(xpTotal(habitId)).isEqualTo(10));

        // ...and the bad ones are gone for good: off the queue, and never retried into the DLQ.
        awaitQueueDrained();
        assertThat(LocalSqs.peekBodies(DEAD_LETTER_QUEUE_URL)).doesNotContainAnyElementsOf(poison);
    }

    @Test
    void aFailureMidProcessingRollsBackAndTheRedeliveryGrantsTheRewardOnce() {
        long userId = insertUser();
        long habitId = insertHabit(userId);
        LocalDate today = LocalDate.now();
        insertEntry(habitId, today, 0);

        // The first attempt fails at the last step, after XP has been computed inside the transaction.
        doThrow(new IllegalStateException("mail service unavailable"))
                .doCallRealMethod()
                .when(emailService).queueCompletionEmail(any(), anyString());

        LocalSqs.send(QUEUE_URL, event(userId, habitId, today));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(xpTotal(habitId)).isEqualTo(10));
        awaitQueueDrained();

        // Two attempts were made, and the reward was applied exactly once.
        verify(emailService, times(2)).queueCompletionEmail(any(), anyString());
        assertThat(xpEarned(habitId, today)).isEqualTo(10);
        assertThat(xpTotal(habitId)).isEqualTo(10);
    }

    @Test
    void anEventThatAlwaysFailsIsRetriedThenMovedToTheDeadLetterQueue() {
        long owner = insertUser();
        long someoneElse = insertUser();
        long habitId = insertHabit(owner);
        // Processing rejects this every time: the user in the event does not own the habit.
        String event = event(someoneElse, habitId, LocalDate.now());

        LocalSqs.send(QUEUE_URL, event);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(LocalSqs.peekBodies(DEAD_LETTER_QUEUE_URL)).contains(event));
        assertThat(xpTotal(habitId)).isZero();
        assertThat(entryCount(habitId)).isZero();
    }

    // --- helpers ---------------------------------------------------------------------------

    private static String event(long userId, long habitId, LocalDate date) {
        return "{\"userId\":" + userId + ",\"habitId\":" + habitId + ",\"date\":\"" + date + "\"}";
    }

    private static void awaitQueueDrained() {
        await().atMost(TIMEOUT).until(() -> LocalSqs.isDrained(QUEUE_URL));
    }

    private long insertUser() {
        return jdbc.queryForObject(
                "insert into app_user (email, password_hash) values (?, 'not-a-real-hash') returning id",
                Long.class,
                "user-" + UUID.randomUUID() + "@example.com");
    }

    private long insertHabit(long userId) {
        return jdbc.queryForObject(
                "insert into habit (user_id, name, description, frequency, goal_period, goal_target_count, "
                        + "xp_total, current_streak, longest_streak, created_at) "
                        + "values (?, 'Read', '', 'DAILY', 'DAILY', 1, 0, 0, 0, now()) returning id",
                Long.class,
                userId);
    }

    private void insertEntry(long habitId, LocalDate date, int xpEarned) {
        jdbc.update(
                "insert into habit_entries (habit_id, completed_date, xp_earned, created_at) values (?, ?, ?, now())",
                habitId, date, xpEarned);
    }

    private int xpTotal(long habitId) {
        return jdbc.queryForObject("select xp_total from habit where id = ?", Integer.class, habitId);
    }

    private int currentStreak(long habitId) {
        return jdbc.queryForObject("select current_streak from habit where id = ?", Integer.class, habitId);
    }

    private int xpEarned(long habitId, LocalDate date) {
        return jdbc.queryForObject(
                "select xp_earned from habit_entries where habit_id = ? and completed_date = ?",
                Integer.class, habitId, date);
    }

    private int entryCount(long habitId) {
        return jdbc.queryForObject("select count(*) from habit_entries where habit_id = ?", Integer.class, habitId);
    }

    private List<String> unlockedAchievements(long userId) {
        return jdbc.queryForList(
                "select a.code from user_achievement ua join achievement a on a.id = ua.achievement_id "
                        + "where ua.user_id = ? order by a.code",
                String.class, userId);
    }
}
