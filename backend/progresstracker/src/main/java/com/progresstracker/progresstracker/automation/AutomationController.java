package com.progresstracker.progresstracker.automation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
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

    @Value("${automation.internal-token:}")
    private String internalToken;

    public AutomationController(StreakResetService streakResetService,
                                 WeeklySummaryService weeklySummaryService) {
        this.streakResetService = streakResetService;
        this.weeklySummaryService = weeklySummaryService;
    }

    @PostMapping("/reset-streaks")
    public Map<String, Object> resetStreaks(@RequestHeader("X-Internal-Token") String token) {
        requireValidToken(token);
        int resetCount = streakResetService.resetBrokenStreaks(LocalDate.now());
        return Map.of("resetCount", resetCount, "ranAt", LocalDate.now().toString());
    }

    @PostMapping("/weekly-summary")
    public Map<String, Object> weeklySummary(@RequestHeader("X-Internal-Token") String token) {
        requireValidToken(token);
        LocalDate weekStart = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
        List<WeeklySummary> summaries = weeklySummaryService.generateSummaries(weekStart);
        return Map.of("summaryCount", summaries.size(), "weekStart", weekStart.toString());
    }

    private void requireValidToken(String token) {
        if (internalToken == null || internalToken.isBlank() || !internalToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or missing internal token");
        }
    }
}
