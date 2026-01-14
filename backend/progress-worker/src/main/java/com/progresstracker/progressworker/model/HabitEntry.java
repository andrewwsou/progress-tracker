package com.progresstracker.progressworker.model;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "habit_entries",
        uniqueConstraints = @UniqueConstraint(columnNames = {"habit_id", "completed_date"})
)
public class HabitEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "habit_id", nullable = false)
    private Habit habit;

    @Column(name = "completed_date", nullable = false)
    private LocalDate completedDate;

    @Column(name = "xp_earned", nullable = false)
    private int xpEarned;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public HabitEntry() {}

    public Long getId() { return id; }
    public Habit getHabit() { return habit; }
    public LocalDate getCompletedDate() { return completedDate; }
    public int getXpEarned() { return xpEarned; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setHabit(Habit habit) { this.habit = habit; }
    public void setCompletedDate(LocalDate completedDate) { this.completedDate = completedDate; }
    public void setXpEarned(int xpEarned) { this.xpEarned = xpEarned; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
