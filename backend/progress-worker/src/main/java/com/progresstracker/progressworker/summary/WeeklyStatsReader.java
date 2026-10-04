package com.progresstracker.progressworker.summary;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Reads one user's week in a single query. Habits created before names were required can have
 * none, so they get a placeholder.
 */
@Component
public class WeeklyStatsReader {

    private final JdbcTemplate jdbc;

    public WeeklyStatsReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public WeekStats read(long userId, LocalDate weekStart) {
        LocalDate weekEnd = weekStart.plusDays(6);
        List<WeekStats.HabitWeek> habits = jdbc.query("""
                        select coalesce(h.name, 'Unnamed habit') as name, h.frequency, h.current_streak, h.longest_streak,
                               count(e.id) as completions,
                               coalesce(sum(e.xp_earned), 0) as xp_earned
                        from habit h
                        left join habit_entries e
                               on e.habit_id = h.id and e.completed_date between ? and ?
                        where h.user_id = ?
                        group by h.id
                        order by h.name, h.id
                        """,
                (rs, row) -> new WeekStats.HabitWeek(
                        rs.getString("name"),
                        rs.getString("frequency"),
                        rs.getInt("completions"),
                        rs.getInt("xp_earned"),
                        rs.getInt("current_streak"),
                        rs.getInt("longest_streak")),
                weekStart, weekEnd, userId);
        return new WeekStats(userId, weekStart, weekEnd, habits);
    }
}
