package com.progresstracker.progresstracker.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;

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

    @Test
    void rejectsAnExpiredToken() {
        // Signed correctly with the right secret, but it expired a minute ago.
        String expired = Jwts.builder()
                .subject("user@example.com")
                .issuedAt(new Date(System.currentTimeMillis() - 120_000))
                .expiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();

        assertFalse(new JwtService(SECRET).isValid(expired));
    }

    @Test
    void signsWithHs256WhateverTheSecretLength() {
        // A 64-byte secret would let jjwt choose HS512 if the algorithm were not named.
        String token = new JwtService("x".repeat(64)).generateToken("user@example.com");

        String header = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);
        assertTrue(header.contains("\"alg\":\"HS256\""));
    }
}
