package com.progresstracker.progresstracker.model;

import jakarta.persistence.*;

/** An achievement's definition. Read-only here: the rows come from a migration (V4__seed_achievements.sql). */
@Entity
@Table(
        name = "achievement",
        uniqueConstraints = {@UniqueConstraint(columnNames = {"code"})}
)
public class Achievement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private Integer threshold;

    @Column(name = "type", nullable = false)
    private String type;

    public Achievement() {
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Integer getThreshold() {
        return threshold;
    }

    public String getType() {
        return type;
    }
}
