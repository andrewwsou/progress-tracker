package com.progresstracker.progressworker.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "user_achievement",
        uniqueConstraints = {@UniqueConstraint(columnNames = {"user_id", "achievement_id"})}
)
public class UserAchievement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "achievement_id", nullable = false)
    private Achievement achievement;

    private LocalDateTime unlockedAt;

    public UserAchievement() {
    }

    public UserAchievement(User user, Achievement achievement, LocalDateTime unlockedAt) {
        this.user = user;
        this.achievement = achievement;
        this.unlockedAt = unlockedAt;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Achievement getAchievement() {
        return achievement;
    }

    public LocalDateTime getUnlockedAt() {
        return unlockedAt;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public void setAchievement(Achievement achievement) {
        this.achievement = achievement;
    }

    public void setUnlockedAt(LocalDateTime unlockedAt) {
        this.unlockedAt = unlockedAt;
    }
}
