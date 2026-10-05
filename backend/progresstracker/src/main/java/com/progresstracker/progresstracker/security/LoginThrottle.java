package com.progresstracker.progresstracker.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Slows down password guessing. Sign-in attempts are counted three ways within a fixed window that
 * starts at the first attempt:
 * <ul>
 *   <li>per email from one client address, with a low limit: guessing one account's password;</li>
 *   <li>per email from anywhere, with a high limit: the same, spread over many addresses;</li>
 *   <li>per client address, whatever the email: one address trying many accounts.</li>
 * </ul>
 * Once any of the three reaches its limit, sign-in is refused for that email from that address,
 * for that email, or from that address, until the window ends, even with the right password.
 * The low limit is on the pair, so someone guessing from one address cannot lock the owner out
 * from theirs; only the high per-email limit applies from everywhere.
 *
 * An attempt is counted before its password is checked, so sign-ins sent at the same time cannot
 * all get in under the limit. It stays counted as a failure unless it succeeds: a successful
 * sign-in clears its pair's count and gives the email and the address their attempts back.
 *
 * The counts live in memory, so each API instance keeps its own. Windows that have ended are
 * dropped whenever the map has doubled since the last sweep, so it holds little more than one
 * window's failures.
 */
@Component
public class LoginThrottle {

    private static final int MIN_SWEEP_SIZE = 1_024;

    private final int maxFailuresPerEmailAndAddress;
    private final int maxFailuresPerEmail;
    private final int maxFailuresPerAddress;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final AtomicInteger sweepAtSize = new AtomicInteger(MIN_SWEEP_SIZE);

    /** The attempts counted since {@code windowStart}: failures, and sign-ins still being checked. */
    private record Attempts(Instant windowStart, int count) {
    }

    /** One count to take, and its limit. */
    private record Limit(String key, int max) {
    }

    public LoginThrottle(@Value("${auth.login.max-failures-per-email-and-address:5}") int maxFailuresPerEmailAndAddress,
                         @Value("${auth.login.max-failures-per-email:100}") int maxFailuresPerEmail,
                         @Value("${auth.login.max-failures-per-address:50}") int maxFailuresPerAddress,
                         @Value("${auth.login.failure-window:15m}") Duration window,
                         Clock clock) {
        this.maxFailuresPerEmailAndAddress = maxFailuresPerEmailAndAddress;
        this.maxFailuresPerEmail = maxFailuresPerEmail;
        this.maxFailuresPerAddress = maxFailuresPerAddress;
        this.window = window;
        this.clock = clock;
    }

    /**
     * Counts one attempt for the email from this address, one for the email, and one for the
     * address, before the password is checked.
     *
     * @throws TooManyAttemptsException if any of the three has no attempts left in its window;
     *                                  then none of them is counted
     */
    public void tryAcquire(String email, String address) {
        Instant now = clock.instant();
        List<Limit> limits = limits(email, address);
        for (int i = 0; i < limits.size(); i++) {
            if (!take(limits.get(i), now)) {
                for (int taken = 0; taken < i; taken++) {
                    giveBack(limits.get(taken).key());
                }
                throw new TooManyAttemptsException(longestWait(limits, now));
            }
        }
        sweepIfGrown(now);
    }

    public void recordSuccess(String email, String address) {
        attempts.remove(pairKey(email, address));
        giveBack(emailKey(email));
        giveBack(addressKey(address));
    }

    /** How many pairs, emails and addresses have attempts on record. */
    int trackedCount() {
        return attempts.size();
    }

    private List<Limit> limits(String email, String address) {
        return List.of(
                new Limit(pairKey(email, address), maxFailuresPerEmailAndAddress),
                new Limit(emailKey(email), maxFailuresPerEmail),
                new Limit(addressKey(address), maxFailuresPerAddress));
    }

    /** Counts one attempt against the key, unless its window has none left. Returns whether it did. */
    private boolean take(Limit limit, Instant now) {
        boolean[] taken = {true};
        attempts.compute(limit.key(), (k, counted) -> {
            if (counted == null || hasEnded(counted, now)) {
                return new Attempts(now, 1);
            }
            if (counted.count() >= limit.max()) {
                taken[0] = false;
                return counted;
            }
            return new Attempts(counted.windowStart(), counted.count() + 1);
        });
        return taken[0];
    }

    private void giveBack(String key) {
        attempts.computeIfPresent(key, (k, counted) ->
                counted.count() > 1 ? new Attempts(counted.windowStart(), counted.count() - 1) : null);
    }

    /** Until every count that is full has its window end: only then can the attempt be counted. */
    private Duration longestWait(List<Limit> limits, Instant now) {
        Duration longest = Duration.ZERO;
        for (Limit limit : limits) {
            Attempts counted = attempts.get(limit.key());
            if (counted != null && counted.count() >= limit.max() && !hasEnded(counted, now)) {
                Duration wait = Duration.between(now, counted.windowStart().plus(window));
                if (wait.compareTo(longest) > 0) {
                    longest = wait;
                }
            }
        }
        return longest;
    }

    private void sweepIfGrown(Instant now) {
        if (attempts.size() < sweepAtSize.get()) {
            return;
        }
        attempts.values().removeIf(counted -> hasEnded(counted, now));
        sweepAtSize.set(Math.max(MIN_SWEEP_SIZE, 2 * attempts.size()));
    }

    private boolean hasEnded(Attempts counted, Instant now) {
        return !now.isBefore(counted.windowStart().plus(window));
    }

    // Prefixed so one kind of key can never be mistaken for another.
    private static String pairKey(String email, String address) {
        return "pair:" + email + "|" + address;
    }

    private static String emailKey(String email) {
        return "email:" + email;
    }

    private static String addressKey(String address) {
        return "address:" + address;
    }
}
