package com.progresstracker.progresstracker.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TimeZoneCatalogTest {

    @Test
    void theDatabasesZonesAreReadOnceOnFirstUse() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("select name from pg_timezone_names", String.class))
                .thenReturn(List.of("UTC", "Europe/Berlin"));
        TimeZoneCatalog catalog = new TimeZoneCatalog(jdbc);
        verify(jdbc, times(0)).queryForList("select name from pg_timezone_names", String.class);

        assertThat(catalog.knows("Europe/Berlin")).isTrue();
        assertThat(catalog.knows("America/Los_Angeles")).isFalse();
        assertThat(catalog.knows("europe/berlin")).isFalse(); // exactly as PostgreSQL names it
        assertThat(catalog.knows(null)).isFalse();

        verify(jdbc, times(1)).queryForList("select name from pg_timezone_names", String.class);
    }
}
