package com.progresstracker.progresstracker.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
public class Habit {

    public enum Frequency {
        DAILY,
        WEEKLY
    }

    public enum GoalPeriod {
        DAILY,
        WEEKLY
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private int xpTotal = 0;
    private int currentStreak = 0;
    private int longestStreak = 0;

    private LocalDate lastCompletedDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @JsonIgnore
    private User user;

    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    private Frequency frequency;

    private LocalDateTime createdAt;

    private Integer goalTargetCount;

    @Enumerated(EnumType.STRING)
    private GoalPeriod goalPeriod;

    @Transient
    private Integer progressCount;

    @Transient
    private Integer progressTargetCount;

    public Habit() {
    }

    public Habit(User user, String name, String description, Frequency frequency) {
        this.user = user;
        this.name = name;
        this.description = description;
        this.frequency = frequency;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Frequency getFrequency() {
        return frequency;
    }

    public void setFrequency(Frequency frequency) {
        this.frequency = frequency;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public int getXpTotal() {
        return xpTotal;
    }

    public void setXpTotal(int xpTotal) {
        this.xpTotal = xpTotal;
    }

    public int getCurrentStreak() {
        return currentStreak;
    }

    public void setCurrentStreak(int currentStreak) {
        this.currentStreak = currentStreak;
    }

    public int getLongestStreak() {
        return longestStreak;
    }

    public void setLongestStreak(int longestStreak) {
        this.longestStreak = longestStreak;
    }

    public LocalDate getLastCompletedDate() {
        return lastCompletedDate;
    }

    public void setLastCompletedDate(LocalDate lastCompletedDate) {
        this.lastCompletedDate = lastCompletedDate;
    }

    public Integer getGoalTargetCount() {
        return goalTargetCount;
    }

    public void setGoalTargetCount(Integer goalTargetCount) {
        this.goalTargetCount = goalTargetCount;
    }

    public GoalPeriod getGoalPeriod() {
        return goalPeriod;
    }

    public void setGoalPeriod(GoalPeriod goalPeriod) {
        this.goalPeriod = goalPeriod;
    }

    public Integer getProgressCount() {
        return progressCount;
    }

    public void setProgressCount(Integer progressCount) {
        this.progressCount = progressCount;
    }

    public Integer getProgressTargetCount() {
        return progressTargetCount;
    }

    public void setProgressTargetCount(Integer progressTargetCount) {
        this.progressTargetCount = progressTargetCount;
    }
}