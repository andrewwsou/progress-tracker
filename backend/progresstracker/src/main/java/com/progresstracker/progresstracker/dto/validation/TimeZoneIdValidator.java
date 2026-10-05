package com.progresstracker.progresstracker.dto.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.ZoneId;

public class TimeZoneIdValidator implements ConstraintValidator<TimeZoneId, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || isRegionId(value);
    }

    /**
     * "UTC" or a region id such as "Europe/Berlin": what browsers report, and what both Java and
     * PostgreSQL resolve with the same daylight-saving rules. Refused: fixed offsets ("+05:00"),
     * abbreviations PostgreSQL reads as fixed offsets ("CET", "EST"), and the "SystemV/" ids
     * Java still lists but PostgreSQL rejects (one such value would fail the streak reset for everyone).
     */
    public static boolean isRegionId(String value) {
        return value.equals("UTC")
                || (value.contains("/") && !value.startsWith("SystemV/") && ZoneId.getAvailableZoneIds().contains(value));
    }
}
