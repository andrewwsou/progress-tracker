package com.progresstracker.progressworker.integration;

import com.progresstracker.progressworker.service.EmailService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real worker (poller thread, AWS SDK client, JPA) against PostgreSQL and an
 * SQS-compatible broker in Docker. The containers and the Spring context are started once
 * and shared by every integration test class; each test creates its own users and habits.
 */
@SpringBootTest
abstract class WorkerIntegrationTestBase {

    /** Same value as the production queue's redrive policy in infra/terraform/sqs.tf. */
    protected static final int MAX_RECEIVE_COUNT = 5;

    protected static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    protected static final String DEAD_LETTER_QUEUE_URL;
    protected static final String QUEUE_URL;

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
    protected JdbcTemplate jdbc;

    @MockitoSpyBean
    protected EmailService emailService;

    /** Runs every call on its own thread, released together by one latch. Returns what each one threw, or null. */
    protected List<Throwable> runAtOnce(List<Callable<?>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch allReady = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Throwable>> pending = new ArrayList<>();
            for (Callable<?> call : calls) {
                pending.add(pool.submit(() -> {
                    allReady.countDown();
                    go.await();
                    try {
                        call.call();
                        return null;
                    } catch (Throwable failure) {
                        return failure;
                    }
                }));
            }

            assertThat(allReady.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Throwable> outcomes = new ArrayList<>();
            for (Future<Throwable> result : pending) {
                outcomes.add(result.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    protected long insertUser() {
        return jdbc.queryForObject(
                "insert into app_user (email, password_hash) values (?, 'not-a-real-hash') returning id",
                Long.class,
                "user-" + UUID.randomUUID() + "@example.com");
    }

    protected long insertHabit(long userId) {
        return jdbc.queryForObject(
                "insert into habit (user_id, name, description, frequency, goal_period, goal_target_count, "
                        + "xp_total, current_streak, longest_streak, created_at) "
                        + "values (?, 'Read', '', 'DAILY', 'DAILY', 1, 0, 0, 0, now()) returning id",
                Long.class,
                userId);
    }

    /** A completion row as the API writes it before the reward is computed: zero XP. */
    protected void insertEntry(long habitId, LocalDate date, int xpEarned) {
        jdbc.update(
                "insert into habit_entries (habit_id, completed_date, xp_earned, created_at) values (?, ?, ?, now())",
                habitId, date, xpEarned);
    }

    protected int xpTotal(long habitId) {
        return jdbc.queryForObject("select xp_total from habit where id = ?", Integer.class, habitId);
    }

    protected int currentStreak(long habitId) {
        return jdbc.queryForObject("select current_streak from habit where id = ?", Integer.class, habitId);
    }

    protected int xpEarned(long habitId, LocalDate date) {
        return jdbc.queryForObject(
                "select xp_earned from habit_entries where habit_id = ? and completed_date = ?",
                Integer.class, habitId, date);
    }

    protected int entryCount(long habitId) {
        return jdbc.queryForObject("select count(*) from habit_entries where habit_id = ?", Integer.class, habitId);
    }

    protected List<String> unlockedAchievements(long userId) {
        return jdbc.queryForList(
                "select a.code from user_achievement ua join achievement a on a.id = ua.achievement_id "
                        + "where ua.user_id = ? order by a.code",
                String.class, userId);
    }
}
