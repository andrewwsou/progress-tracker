package com.progresstracker.progresstracker.service;

import com.progresstracker.progresstracker.model.User;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * What day it is for a user. Completions, goals, and streaks are counted in the user's own
 * calendar days: a habit done at 8 pm in Los Angeles belongs to that day, even though it is
 * already tomorrow in UTC.
 */
@Component
public class UserCalendar {

    private final Clock clock;

    public UserCalendar(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today(User user) {
        return LocalDate.now(clock.withZone(user.zone()));
    }
}
