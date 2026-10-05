package com.progresstracker.progresstracker.dto;

import com.progresstracker.progresstracker.dto.validation.TimeZoneId;
import jakarta.validation.constraints.NotBlank;

/** What a user may change about their own account. */
public record ProfileRequest(@NotBlank @TimeZoneId String timeZone) {
}
