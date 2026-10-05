package com.progresstracker.progresstracker.dto.validation;

import com.progresstracker.progresstracker.service.TimeZoneCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TimeZoneIdValidatorTest {

    @Test
    void aZoneJavaKnowsButTheDatabaseDoesNotIsRefused() {
        // The database's time zone data is older than the JVM's.
        TimeZoneIdValidator validator = new TimeZoneIdValidator(catalogOf("UTC", "Europe/Berlin"));

        assertThat(ZoneId.getAvailableZoneIds()).contains("America/Los_Angeles");
        assertThat(validator.isValid("America/Los_Angeles", null)).isFalse();
        assertThat(validator.isValid("Europe/Berlin", null)).isTrue();
        assertThat(validator.isValid("UTC", null)).isTrue();
        assertThat(validator.isValid(null, null)).isTrue(); // @NotBlank's job
    }

    @Test
    void aNameTheDatabaseKnowsIsStillRefusedUnlessItIsARegion() {
        // PostgreSQL lists these too, but reads them as fixed offsets or not at all.
        TimeZoneIdValidator validator = new TimeZoneIdValidator(catalogOf("CET", "EST", "SystemV/PST8", "Mars/Olympus_Mons"));

        for (String zone : List.of("CET", "EST", "SystemV/PST8", "Mars/Olympus_Mons")) {
            assertThat(validator.isValid(zone, null)).as(zone).isFalse();
        }
    }

    private static TimeZoneCatalog catalogOf(String... names) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("select name from pg_timezone_names", String.class)).thenReturn(List.of(names));
        return new TimeZoneCatalog(jdbc);
    }
}
