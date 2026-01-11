package com.progresstracker.progresstracker.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    @JsonIgnore
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

    public HabitEntry(Habit habit, LocalDate completedDate, int xpEarned) {
        this.habit = habit;
        this.completedDate = completedDate;
        this.xpEarned = xpEarned;
    }

    public Long getId() { return id; }

    public Habit getHabit() { return habit; }
    public void setHabit(Habit habit) { this.habit = habit; }

    public LocalDate getCompletedDate() { return completedDate; }
    public void setCompletedDate(LocalDate completedDate) { this.completedDate = completedDate; }

    public int getXpEarned() { return xpEarned; }
    public void setXpEarned(int xpEarned) { this.xpEarned = xpEarned; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
