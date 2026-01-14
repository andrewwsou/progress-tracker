package com.progresstracker.progresstracker.model;

import jakarta.persistence.*;

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

    public Achievement(String code, String name, String description, Integer threshold, String type) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.threshold = threshold;
        this.type = type;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
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

    public Integer getThreshold() {
        return threshold;
    }

    public void setThreshold(Integer threshold) {
        this.threshold = threshold;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
