package com.progresstracker.progresstracker.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * The time zones this database knows. The hourly streak reset works out each user's date in
 * PostgreSQL, whose time zone data can be older than the JVM's; a zone only Java knows would be
 * read there as UTC. So a zone is only accepted when both know it.
 *
 * Read once, on first use, and kept: the list only changes when PostgreSQL is upgraded.
 */
@Component
public class TimeZoneCatalog {

    private final JdbcTemplate jdbc;
    private volatile Set<String> names;

    public TimeZoneCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether PostgreSQL knows a zone by exactly this name. */
    public boolean knows(String zone) {
        Set<String> known = names;
        if (known == null) {
            // Two first uses at once may both read the list; they read the same one.
            known = Set.copyOf(jdbc.queryForList("select name from pg_timezone_names", String.class));
            names = known;
        }
        return zone != null && known.contains(zone);
    }
}
