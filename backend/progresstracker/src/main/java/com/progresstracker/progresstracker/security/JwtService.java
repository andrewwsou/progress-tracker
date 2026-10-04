package com.progresstracker.progresstracker.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    // HS256 needs a key of at least 256 bits.
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final long expirationMs = 1000L * 60 * 60 * 24;

    public JwtService(@Value("${jwt.secret:}") String secret) {
        byte[] secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set to at least " + MIN_SECRET_BYTES
                            + " bytes (generate one with: openssl rand -base64 48)");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
    }

    public String generateToken(String email) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + expirationMs);

        // HS256 named explicitly: given only the key, jjwt picks the strongest algorithm the key
        // allows, and a 64-byte secret would silently switch every new token to HS512.
        return Jwts.builder()
                .subject(email)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
