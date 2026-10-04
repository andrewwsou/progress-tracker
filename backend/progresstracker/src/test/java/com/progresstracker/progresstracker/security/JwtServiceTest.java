package com.progresstracker.progresstracker.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private static final String SECRET = "test-only-secret-that-is-at-least-32-bytes";
    private static final String OTHER_SECRET = "another-test-only-secret-of-32-bytes-min";

    @Test
    void refusesToStartWithoutASecret() {
        assertThrows(IllegalStateException.class, () -> new JwtService(""));
        assertThrows(IllegalStateException.class, () -> new JwtService(null));
    }

    @Test
    void refusesToStartWithAShortSecret() {
        assertThrows(IllegalStateException.class, () -> new JwtService("too-short"));
    }

    @Test
    void issuedTokenRoundTripsTheEmail() {
        JwtService jwt = new JwtService(SECRET);

        String token = jwt.generateToken("user@example.com");

        assertTrue(jwt.isValid(token));
        assertEquals("user@example.com", jwt.extractEmail(token));
    }

    @Test
    void rejectsTokenSignedWithADifferentSecret() {
        String foreignToken = new JwtService(OTHER_SECRET).generateToken("user@example.com");

        assertFalse(new JwtService(SECRET).isValid(foreignToken));
    }

    @Test
    void rejectsGarbage() {
        assertFalse(new JwtService(SECRET).isValid("not-a-jwt"));
    }
}
