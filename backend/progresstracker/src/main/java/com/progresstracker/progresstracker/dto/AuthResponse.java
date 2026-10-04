package com.progresstracker.progresstracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record AuthResponse(@Schema(requiredMode = REQUIRED) String token) {
}
