package com.progresstracker.progresstracker.dto;

import java.time.LocalDateTime;

public record UserAchievementDto(
        long userAchievementId,
        long achievementId,
        String code,
        String name,
        String description,
        Integer threshold,
        String type,
        LocalDateTime unlockedAt
) {}
