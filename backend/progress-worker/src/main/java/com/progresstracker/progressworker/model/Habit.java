package com.progresstracker.progressworker.model;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
public class Habit {

    public enum Frequency { DAILY, WEEKLY }
    public enum GoalPeriod { DAILY, WEEKLY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private int xpTotal = 0;
    private int currentStreak = 0;
    private int longestStreak = 0;

    private LocalDate lastCompletedDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    private String name;
    private String description;

    @Enumerated(EnumType.STRING)
    private Frequency frequency;

    private LocalDateTime createdAt;

    private Integer goalTargetCount;

    @Enumerated(EnumType.STRING)
    private GoalPeriod goalPeriod;

    public Habit() {}

    public Long getId() { return id; }
    public int getXpTotal() { return xpTotal; }
    public int getCurrentStreak() { return currentStreak; }
    public int getLongestStreak() { return longestStreak; }
    public LocalDate getLastCompletedDate() { return lastCompletedDate; }
    public User getUser() { return user; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Frequency getFrequency() { return frequency; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public Integer getGoalTargetCount() { return goalTargetCount; }
    public GoalPeriod getGoalPeriod() { return goalPeriod; }

    public void setId(Long id) { this.id = id; }
    public void setXpTotal(int xpTotal) { this.xpTotal = xpTotal; }
    public void setCurrentStreak(int currentStreak) { this.currentStreak = currentStreak; }
    public void setLongestStreak(int longestStreak) { this.longestStreak = longestStreak; }
    public void setLastCompletedDate(LocalDate lastCompletedDate) { this.lastCompletedDate = lastCompletedDate; }
    public void setUser(User user) { this.user = user; }
    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setFrequency(Frequency frequency) { this.frequency = frequency; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setGoalTargetCount(Integer goalTargetCount) { this.goalTargetCount = goalTargetCount; }
    public void setGoalPeriod(GoalPeriod goalPeriod) { this.goalPeriod = goalPeriod; }
}
