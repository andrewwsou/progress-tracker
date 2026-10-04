package com.progresstracker.progresstracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record UserAchievementDto(
        @Schema(requiredMode = REQUIRED) long userAchievementId,
        @Schema(requiredMode = REQUIRED) long achievementId,
        @Schema(requiredMode = REQUIRED) String code,
        @Schema(requiredMode = REQUIRED) String name,
        @Schema(requiredMode = REQUIRED) String description,
        @Schema(requiredMode = REQUIRED) Integer threshold,
        @Schema(requiredMode = REQUIRED) String type,
        @Schema(requiredMode = REQUIRED) OffsetDateTime unlockedAt
) {}
