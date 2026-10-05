package com.progresstracker.progresstracker.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.time.Duration;

/** Sign-in refused for now: 429, with a Retry-After header giving the seconds to wait. */
public class TooManyAttemptsException extends ErrorResponseException {

    static final String DETAIL = "Too many failed sign-in attempts. Try again later.";

    public TooManyAttemptsException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, DETAIL), null);
        long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000); // rounded up: never too early
        getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
    }
}
