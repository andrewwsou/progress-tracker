package com.progresstracker.progresstracker.automation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Endpoints meant to be invoked by scheduled Lambdas (see infra/lambda), not
 * end users - hence the shared-secret header instead of JWT auth. In a real
 * AWS deployment this would run over a private VPC link or be signed with
 * IAM/SigV4; a static token is the minimal equivalent for a local/demo setup.
 */
@RestController
@RequestMapping("/api/internal/automations")
public class AutomationController {

    private final StreakResetService streakResetService;
    private final WeeklySummaryService weeklySummaryService;
    private final Clock clock;

    @Value("${automation.internal-token:}")
    private String internalToken;

    public AutomationController(StreakResetService streakResetService,
                                 WeeklySummaryService weeklySummaryService,
                                 Clock clock) {
        this.streakResetService = streakResetService;
        this.weeklySummaryService = weeklySummaryService;
        this.clock = clock;
    }

    @PostMapping("/reset-streaks")
    public Map<String, Object> resetStreaks(@RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireValidToken(token);
        Instant now = clock.instant();
        int resetCount = streakResetService.resetBrokenStreaks(now);
        return Map.of("resetCount", resetCount, "ranAt", now.toString());
    }

    /**
     * Asks the worker to write each active user's summary of a week: last week by default, or the
     * week containing {@code weekStart} (any day of it) to backfill or test a specific week.
     */
    @PostMapping("/weekly-summary")
    public Map<String, Object> weeklySummary(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        requireValidToken(token);
        // Last week by the UTC calendar. Run on Monday afternoon UTC, that is last week in every zone.
        LocalDate week = WeeklySummaryService.startOfWeek(weekStart != null ? weekStart : LocalDate.now(clock).minusWeeks(1));
        int requested = weeklySummaryService.requestSummaries(week);
        return Map.of("requestedCount", requested, "weekStart", week.toString());
    }

    private void requireValidToken(String token) {
        if (internalToken == null || internalToken.isBlank() || !internalToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or missing internal token");
        }
    }
}
