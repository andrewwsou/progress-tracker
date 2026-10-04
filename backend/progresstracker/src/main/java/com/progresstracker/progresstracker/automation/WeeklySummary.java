package com.progresstracker.progresstracker.automation;

import java.time.LocalDate;

public record WeeklySummary(
        Long userId,
        String email,
        LocalDate weekStart,
        LocalDate weekEnd,
        int habitCount,
        long completions,
        long xpEarned
) {
}
