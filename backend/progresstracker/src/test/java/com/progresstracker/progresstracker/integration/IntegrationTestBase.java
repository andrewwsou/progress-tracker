package com.progresstracker.progresstracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole API on a random port against a real PostgreSQL running in Docker,
 * and drives it over HTTP exactly as a client would.
 *
 * The container is started once and shared by every integration test class
 * (the Testcontainers "singleton container" pattern); it is removed when the JVM exits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class IntegrationTestBase {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("jwt.secret", () -> "integration-test-secret-at-least-32-bytes-long");
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    /** Registers a user through the API and returns their JWT. */
    protected String registerUser(String email) {
        ResponseEntity<JsonNode> response = rest.postForEntity(
                "/api/auth/register",
                Map.of("email", email, "password", "correct-horse-battery-staple"),
                JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody().get("token").asText();
    }

    /** Creates a daily habit through the API and returns its id. */
    protected long createDailyHabit(String token, String name) {
        ResponseEntity<JsonNode> response = rest.exchange(
                "/api/habits",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", name, "description", "", "frequency", "DAILY"), bearer(token)),
                JsonNode.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return response.getBody().get("id").asLong();
    }

    protected ResponseEntity<JsonNode> completeHabit(String token, long habitId) {
        return send(HttpMethod.POST, "/api/habits/" + habitId + "/complete", token, null);
    }

    /** Sends a JSON request, with a bearer token when one is given, and returns the raw response. */
    protected ResponseEntity<JsonNode> send(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = token == null ? jsonHeaders() : bearer(token);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    /**
     * Fires {@code requests} completions of the same habit at the same instant and returns
     * every HTTP status. All threads wait on one latch so they hit the API together.
     */
    protected List<Integer> completeConcurrently(String token, long habitId, int requests) throws Exception {
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            calls.add(() -> completeHabit(token, habitId).getStatusCode().value());
        }
        return runAtOnce(calls);
    }

    /** Runs every call on its own thread, released together by one latch, and returns the results in order. */
    protected <R> List<R> runAtOnce(List<Callable<R>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch allReady = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<R>> pending = new ArrayList<>();
            for (Callable<R> call : calls) {
                pending.add(pool.submit(() -> {
                    allReady.countDown();
                    go.await();
                    return call.call();
                }));
            }

            assertThat(allReady.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<R> results = new ArrayList<>();
            for (Future<R> result : pending) {
                results.add(result.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    protected long userIdFor(String email) {
        return jdbc.queryForObject("select id from app_user where email = ?", Long.class, email);
    }

    protected int entryCount(long habitId) {
        return jdbc.queryForObject("select count(*) from habit_entries where habit_id = ?", Integer.class, habitId);
    }

    protected int xpTotal(long habitId) {
        return jdbc.queryForObject("select xp_total from habit where id = ?", Integer.class, habitId);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
