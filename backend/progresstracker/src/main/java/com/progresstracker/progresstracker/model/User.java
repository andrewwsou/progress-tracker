package com.progresstracker.progresstracker.model;

import jakarta.persistence.*;

import java.time.ZoneId;

@Entity
@Table(name = "app_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    /** IANA time zone id; the user's calendar days are counted in it. */
    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone = "UTC";

    /**
     * Carried in every token issued to the user; a token with an older version is refused. Only
     * ever raised by one UPDATE (see UserRepository), never written from the entity, so saving a
     * copy loaded earlier cannot put back a version that signing out replaced.
     */
    @Column(name = "token_version", nullable = false, updatable = false)
    private int tokenVersion;

    public User() {}

    public User(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public ZoneId zone() {
        return ZoneId.of(timeZone);
    }
}
