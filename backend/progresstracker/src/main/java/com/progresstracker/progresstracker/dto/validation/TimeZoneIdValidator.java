package com.progresstracker.progresstracker.dto.validation;

import com.progresstracker.progresstracker.service.TimeZoneCatalog;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.ZoneId;

/** Created by Spring's validator factory, which passes in the catalog of the database's zones. */
public class TimeZoneIdValidator implements ConstraintValidator<TimeZoneId, String> {

    private final TimeZoneCatalog catalog;

    public TimeZoneIdValidator(TimeZoneCatalog catalog) {
        this.catalog = catalog;
    }

    /** A region id that both Java and this database know. */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || (isRegionId(value) && catalog.knows(value));
    }

    /**
     * "UTC" or a region id such as "Europe/Berlin": what browsers report, and what both Java and
     * PostgreSQL resolve with the same daylight-saving rules. Refused: fixed offsets ("+05:00"),
     * abbreviations PostgreSQL reads as fixed offsets ("CET", "EST"), and the "SystemV/" ids
     * Java still lists but PostgreSQL rejects. Whether this database knows the id is checked
     * separately, with {@link TimeZoneCatalog}.
     */
    public static boolean isRegionId(String value) {
        return value.equals("UTC")
                || (value.contains("/") && !value.startsWith("SystemV/") && ZoneId.getAvailableZoneIds().contains(value));
    }
}
