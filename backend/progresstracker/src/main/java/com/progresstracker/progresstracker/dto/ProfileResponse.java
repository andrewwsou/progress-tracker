package com.progresstracker.progresstracker.dto;

import com.progresstracker.progresstracker.model.User;
import io.swagger.v3.oas.annotations.media.Schema;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

public record ProfileResponse(
        @Schema(requiredMode = REQUIRED) String email,
        @Schema(requiredMode = REQUIRED, description = "IANA time zone; the user's days are counted in it")
        String timeZone
) {

    public static ProfileResponse from(User user) {
        return new ProfileResponse(user.getEmail(), user.getTimeZone());
    }
}
