package com.progresstracker.progresstracker.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The live-update stream: a notification on the habit_events channel (what the worker sends when
 * a reward commits) reaches the open stream of that user, and only that user. Signing out ends
 * the user's open streams.
 */
class EventStreamIT extends IntegrationTestBase {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    /** Opens the stream and collects its lines in the background. */
    private List<String> openStream(String token, List<CompletableFuture<?>> open) {
        List<String> lines = new CopyOnWriteArrayList<>();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "text/event-stream")
                .build();
        CompletableFuture<HttpResponse<Stream<String>>> response = http.sendAsync(request, HttpResponse.BodyHandlers.ofLines());
        open.add(response.thenAcceptAsync(r -> r.body().forEach(lines::add)));
        return lines;
    }

    @Test
    void aUserReceivesTheirOwnEventsAndNoOneElses() {
        String email = uniqueEmail();
        String token = registerUser(email);
        long userId = userIdFor(email);
        long otherUserId = userIdFor(registerUserReturningEmail());
        List<CompletableFuture<?>> open = new ArrayList<>();
        try {
            List<String> lines = openStream(token, open);
            await().atMost(Duration.ofSeconds(10)).until(() -> lines.contains("event:ready"));

            // The listener connects in the background, so keep notifying until it is listening.
            await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).until(() -> {
                notify(otherUserId, 2);
                notify(userId, 1);
                return lines.contains("event:reward");
            });

            assertThat(lines).anyMatch(l -> l.startsWith("data:") && l.contains("\"userId\":" + userId));
            assertThat(lines).noneMatch(l -> l.contains("\"userId\":" + otherUserId));
        } finally {
            open.forEach(f -> f.cancel(true));
        }
    }

    @Test
    void signingOutEndsTheUsersOpenStreams() {
        String token = registerUser(uniqueEmail());
        List<CompletableFuture<?>> open = new ArrayList<>();
        try {
            List<String> lines = openStream(token, open);
            await().atMost(Duration.ofSeconds(10)).until(() -> lines.contains("event:ready"));

            assertThat(send(HttpMethod.POST, "/api/auth/logout", token, null).getStatusCode().value()).isEqualTo(204);

            // Ended by the server at once, not left open until it times out, and not reopened.
            await().atMost(Duration.ofSeconds(10)).until(() -> open.get(0).isDone());
            assertThat(lines).noneMatch(l -> l.contains("signedOut"));
            assertThat(send(HttpMethod.GET, "/api/events", token, null).getStatusCode().value()).isEqualTo(401);
        } finally {
            open.forEach(f -> f.cancel(true));
        }
    }

    @Test
    void aSignOutOnAnotherInstanceEndsTheStreamsHereToo() {
        String email = uniqueEmail();
        String token = registerUser(email);
        long userId = userIdFor(email);
        List<CompletableFuture<?>> open = new ArrayList<>();
        try {
            List<String> lines = openStream(token, open);
            await().atMost(Duration.ofSeconds(10)).until(() -> lines.contains("event:ready"));

            // What the instance that handled the sign-out sends. The listener connects in the
            // background, so keep sending until it hears one.
            await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).until(() -> {
                jdbc.queryForList("select pg_notify('habit_events', ?)",
                        "{\"type\":\"signedOut\",\"userId\":" + userId + "}");
                return open.get(0).isDone();
            });

            assertThat(lines).noneMatch(l -> l.contains("signedOut"));
        } finally {
            open.forEach(f -> f.cancel(true));
        }
    }

    @Test
    void theStreamNeedsALogin() {
        assertThat(send(HttpMethod.GET, "/api/events", null, null).getStatusCode().value()).isEqualTo(401);
    }

    private void notify(long userId, long habitId) {
        jdbc.queryForList("select pg_notify('habit_events', ?)",
                "{\"type\":\"reward\",\"userId\":" + userId + ",\"habitId\":" + habitId + "}");
    }

    private String registerUserReturningEmail() {
        String email = uniqueEmail();
        registerUser(email);
        return email;
    }
}
