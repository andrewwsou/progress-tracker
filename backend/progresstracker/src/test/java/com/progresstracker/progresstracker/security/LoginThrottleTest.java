package com.progresstracker.progresstracker.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginThrottleTest {

    private static final String EMAIL = "victim@example.com";
    private static final String IP = "203.0.113.7";
    private static final String OTHER_IP = "198.51.100.1";

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-05T12:00:00Z"));
    private final LoginThrottle throttle = new LoginThrottle(5, 100, 50, Duration.ofMinutes(15), clock);

    @Test
    void fiveFailuresForOneEmailFromOneAddressBlockThatPairUntilTheWindowEnds() {
        for (int i = 0; i < 5; i++) {
            assertThatCode(() -> throttle.tryAcquire(EMAIL, IP)).doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> throttle.tryAcquire(EMAIL, IP))
                .isInstanceOfSatisfying(TooManyAttemptsException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(429);
                    assertThat(e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("900");
                });
        // The owner, from their own address, is not locked out by someone guessing from another.
        assertThatCode(() -> throttle.tryAcquire(EMAIL, OTHER_IP)).doesNotThrowAnyException();
        // Other accounts are unaffected, from either address.
        assertThatCode(() -> throttle.tryAcquire("someone-else@example.com", IP)).doesNotThrowAnyException();

        clock.advance(Duration.ofMinutes(10));
        assertThatThrownBy(() -> throttle.tryAcquire(EMAIL, IP))
                .isInstanceOfSatisfying(TooManyAttemptsException.class, e ->
                        assertThat(e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("300"));

        clock.advance(Duration.ofMinutes(5));
        assertThatCode(() -> throttle.tryAcquire(EMAIL, IP)).doesNotThrowAnyException();
    }

    @Test
    void aHundredFailuresForOneEmailFromAnywhereBlockItEverywhere() {
        // Guesses spread over 20 addresses, 5 from each: no pair reaches its limit.
        for (int address = 0; address < 20; address++) {
            for (int i = 0; i < 5; i++) {
                throttle.tryAcquire(EMAIL, "10.0.0." + address);
            }
        }

        assertThatThrownBy(() -> throttle.tryAcquire(EMAIL, OTHER_IP))
                .isInstanceOfSatisfying(TooManyAttemptsException.class, e ->
                        assertThat(e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("900"));
        assertThatCode(() -> throttle.tryAcquire("someone-else@example.com", OTHER_IP)).doesNotThrowAnyException();
    }

    @Test
    void attemptsAreCountedBeforeTheirPasswordsAreChecked() {
        // Twenty guesses arrive before any of them has been checked: only five get a password check.
        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            try {
                throttle.tryAcquire(EMAIL, IP);
                allowed++;
            } catch (TooManyAttemptsException e) {
                // refused
            }
        }

        assertThat(allowed).isEqualTo(5);
    }

    @Test
    void attemptsMadeAtTheSameTimeCannotGetPastTheLimitTogether() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        try {
            List<Future<?>> pending = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                pending.add(pool.submit(() -> {
                    go.await();
                    try {
                        throttle.tryAcquire(EMAIL, IP);
                        allowed.incrementAndGet();
                    } catch (TooManyAttemptsException e) {
                        // refused
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> result : pending) {
                result.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed.get()).isEqualTo(5);
    }

    @Test
    void aSuccessfulSignInClearsThePairsFailures() {
        for (int i = 0; i < 4; i++) {
            throttle.tryAcquire(EMAIL, IP); // wrong passwords
        }
        throttle.tryAcquire(EMAIL, IP);
        throttle.recordSuccess(EMAIL, IP); // then the right one
        for (int i = 0; i < 4; i++) {
            throttle.tryAcquire(EMAIL, IP);
        }

        assertThatCode(() -> throttle.tryAcquire(EMAIL, IP)).doesNotThrowAnyException();
    }

    @Test
    void aSuccessfulSignInGivesTheEmailItsAttemptBack() {
        for (int address = 0; address < 20; address++) {
            for (int i = 0; i < 5; i++) {
                if (address < 19 || i < 4) {
                    throttle.tryAcquire(EMAIL, "10.0.0." + address); // 99 wrong passwords
                }
            }
        }
        throttle.tryAcquire(EMAIL, IP);
        throttle.recordSuccess(EMAIL, IP); // the 100th is the owner, with the right one

        // Still 1 attempt left for the email, not 0.
        assertThatCode(() -> throttle.tryAcquire(EMAIL, OTHER_IP)).doesNotThrowAnyException();
        assertThatThrownBy(() -> throttle.tryAcquire(EMAIL, "192.0.2.1")).isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void manyFailuresFromOneAddressBlockEveryEmailFromIt() {
        for (int i = 0; i < 50; i++) {
            throttle.tryAcquire("guess-" + i + "@example.com", IP);
        }

        assertThatThrownBy(() -> throttle.tryAcquire("new-target@example.com", IP)).isInstanceOf(TooManyAttemptsException.class);
        assertThatCode(() -> throttle.tryAcquire("new-target@example.com", OTHER_IP)).doesNotThrowAnyException();
    }

    @Test
    void successfulSignInsDoNotUseUpTheAddressesAttempts() {
        // Many people behind one address (an office, a phone network) all signing in.
        for (int i = 0; i < 60; i++) {
            throttle.tryAcquire("user-" + i + "@example.com", IP);
            throttle.recordSuccess("user-" + i + "@example.com", IP);
        }

        assertThatCode(() -> throttle.tryAcquire(EMAIL, IP)).doesNotThrowAnyException();
    }

    @Test
    void anAttemptRefusedForItsAddressCountsAgainstNeitherItsPairNorItsEmail() {
        LoginThrottle lowEmailLimit = new LoginThrottle(5, 8, 50, Duration.ofMinutes(15), clock);
        for (int i = 0; i < 50; i++) {
            lowEmailLimit.tryAcquire("guess-" + i + "@example.com", IP);
        }
        clock.advance(Duration.ofMinutes(10));
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> lowEmailLimit.tryAcquire(EMAIL, IP)).isInstanceOf(TooManyAttemptsException.class);
        }

        // Once the address's window ends, the refused attempts have left nothing behind: the pair
        // and the email still have every attempt.
        clock.advance(Duration.ofMinutes(5));
        assertThatCode(() -> lowEmailLimit.tryAcquire(EMAIL, IP)).doesNotThrowAnyException();
        for (int i = 0; i < 7; i++) {
            String address = "10.0.0." + (i % 2);
            assertThatCode(() -> lowEmailLimit.tryAcquire(EMAIL, address)).doesNotThrowAnyException();
        }
    }

    @Test
    void anAttemptRefusedForItsEmailDoesNotCountAgainstItsPair() {
        LoginThrottle lowEmailLimit = new LoginThrottle(5, 8, 50, Duration.ofMinutes(15), clock);
        for (int i = 0; i < 8; i++) {
            lowEmailLimit.tryAcquire(EMAIL, "10.0.0." + (i % 2));
        }
        clock.advance(Duration.ofMinutes(10));
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> lowEmailLimit.tryAcquire(EMAIL, IP)).isInstanceOf(TooManyAttemptsException.class);
        }

        clock.advance(Duration.ofMinutes(5));
        assertThatCode(() -> lowEmailLimit.tryAcquire(EMAIL, IP)).doesNotThrowAnyException();
    }

    @Test
    void whenSeveralAreBlockedTheWaitIsTheLongest() {
        LoginThrottle lowEmailLimit = new LoginThrottle(5, 8, 50, Duration.ofMinutes(15), clock);
        for (int i = 0; i < 8; i++) {
            lowEmailLimit.tryAcquire(EMAIL, "10.0.0." + (i % 2));
        }
        clock.advance(Duration.ofMinutes(5));
        for (int i = 0; i < 50; i++) {
            lowEmailLimit.tryAcquire("guess-" + i + "@example.com", IP);
        }

        // The email is free in 10 minutes, the address in 15. The email is checked first.
        assertThatThrownBy(() -> lowEmailLimit.tryAcquire(EMAIL, IP))
                .isInstanceOfSatisfying(TooManyAttemptsException.class, e ->
                        assertThat(e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("900"));
    }

    @Test
    void windowsThatHaveEndedAreForgotten() {
        recordDistinctFailures(600, "old");
        assertThat(throttle.trackedCount()).isEqualTo(1_800); // one pair, email and address each

        clock.advance(Duration.ofMinutes(16));
        recordDistinctFailures(600, "new");

        // Only the current window's failures are still held.
        assertThat(throttle.trackedCount()).isEqualTo(1_800);
    }

    private void recordDistinctFailures(int count, String prefix) {
        for (int i = 0; i < count; i++) {
            throttle.tryAcquire(prefix + "-" + i + "@example.com", prefix + "-ip-" + i);
        }
    }

    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
