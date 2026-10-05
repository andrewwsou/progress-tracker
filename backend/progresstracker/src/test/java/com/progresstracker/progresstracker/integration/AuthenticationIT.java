package com.progresstracker.progresstracker.integration;

import com.progresstracker.progresstracker.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/** Signing up, signing in, and signing out, through the API and against the real database. */
@TestPropertySource(properties = "queue.enabled=false")
class AuthenticationIT extends IntegrationTestBase {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private JwtService jwtService;

    @Test
    void emailsMatchHoweverTheyAreCapitalised() {
        String id = UUID.randomUUID().toString();

        assertThat(register("Mixed-" + id + "@Example.com").getStatusCode().value()).isEqualTo(200);

        // Stored in lower case, signed in to in any case, and one account per mailbox.
        assertThat(jdbc.queryForObject("select count(*) from app_user where email = ?", Integer.class,
                "mixed-" + id + "@example.com")).isEqualTo(1);
        assertThat(login("mixed-" + id + "@example.com", PASSWORD).getStatusCode().value()).isEqualTo(200);
        assertThat(login(" MIXED-" + id + "@EXAMPLE.COM ", PASSWORD).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<JsonNode> again = register("MIXED-" + id + "@example.com");
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody().get("detail").asString()).isEqualTo("Email already used");
    }

    @Test
    void aTokenIssuedBeforeEmailsWereStoredInLowerCaseStillWorks() {
        String id = UUID.randomUUID().toString();
        register("mixed-" + id + "@example.com");

        // Before V3 the address was stored, and put in tokens, as it was typed.
        String olderToken = jwtService.generateToken("Mixed-" + id + "@Example.com", 0);

        assertThat(profileStatus(olderToken)).isEqualTo(200);
    }

    @Test
    void repeatedFailedSignInsAreRefusedForAWhileEvenWithTheRightPassword() {
        String email = uniqueEmail();
        registerUser(email);

        for (int i = 0; i < 5; i++) {
            assertThat(login(email, "wrong-password-" + i).getStatusCode().value()).isEqualTo(401);
        }
        ResponseEntity<JsonNode> sixth = login(email, "wrong-password-5");

        assertThat(sixth.getStatusCode().value()).isEqualTo(429);
        assertThat(sixth.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(sixth.getBody().get("detail").asString()).isEqualTo("Too many failed sign-in attempts. Try again later.");
        assertThat(Integer.parseInt(sixth.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(1, 15 * 60);

        // Checked before the password: the right one is refused too while the email is blocked
        // from this address.
        assertThat(login(email, PASSWORD).getStatusCode().value()).isEqualTo(429);
        // Other accounts can still sign in from the same address.
        String other = uniqueEmail();
        registerUser(other);
        assertThat(login(other, PASSWORD).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void guessingFromOneAddressDoesNotLockTheOwnerOutFromAnother() {
        String email = uniqueEmail();
        registerUser(email);
        String guesser = "203.0.113.10";

        for (int i = 0; i < 5; i++) {
            assertThat(loginFrom(guesser, email, "wrong-password-" + i)).isEqualTo(401);
        }

        // The forwarded address is the one counted (the test connects from 127.0.0.1, a trusted
        // proxy): the guesser is now refused even with the right password...
        assertThat(loginFrom(guesser, email, PASSWORD)).isEqualTo(429);
        // ...but the owner, somewhere else, signs in.
        assertThat(loginFrom("198.51.100.20", email, PASSWORD)).isEqualTo(200);
        assertThat(login(email, PASSWORD).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void guessesSentAllAtOnceGetNoMoreTriesThanGuessesSentOneByOne() throws Exception {
        String email = uniqueEmail();
        registerUser(email);
        List<Callable<Integer>> guesses = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String guess = "wrong-password-" + i;
            guesses.add(() -> login(email, guess).getStatusCode().value());
        }

        List<Integer> statuses = runAtOnce(guesses);

        assertThat(statuses).filteredOn(status -> status == 401).hasSize(5);
        assertThat(statuses).filteredOn(status -> status == 429).hasSize(15);
    }

    @Test
    void signingOutEndsEverySessionOfThatUserAndNoOneElses() {
        String email = uniqueEmail();
        String firstSession = registerUser(email);
        String secondSession = login(email, PASSWORD).getBody().get("token").asString();
        String someoneElse = registerUser(uniqueEmail());

        ResponseEntity<JsonNode> logout = send(HttpMethod.POST, "/api/auth/logout", firstSession, null);

        assertThat(logout.getStatusCode().value()).isEqualTo(204);
        assertThat(profileStatus(firstSession)).isEqualTo(401);
        assertThat(profileStatus(secondSession)).isEqualTo(401); // on every device
        assertThat(profileStatus(someoneElse)).isEqualTo(200);

        // Signing in again gives a token that works.
        String fresh = login(email, PASSWORD).getBody().get("token").asString();
        assertThat(profileStatus(fresh)).isEqualTo(200);
    }

    @Test
    void signingOutNeedsAToken() {
        assertThat(send(HttpMethod.POST, "/api/auth/logout", null, null).getStatusCode().value()).isEqualTo(401);
    }

    private ResponseEntity<JsonNode> register(String email) {
        return send(HttpMethod.POST, "/api/auth/register", null, Map.of("email", email, "password", PASSWORD));
    }

    private ResponseEntity<JsonNode> login(String email, String password) {
        return send(HttpMethod.POST, "/api/auth/login", null, Map.of("email", email, "password", password));
    }

    /** Signs in as a client at {@code address}, forwarded the way a load balancer in front of the API would. */
    private int loginFrom(String address, String email, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", address);
        return rest.exchange("/api/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", password), headers), JsonNode.class)
                .getStatusCode().value();
    }

    private int profileStatus(String token) {
        return send(HttpMethod.GET, "/api/me", token, null).getStatusCode().value();
    }
}
