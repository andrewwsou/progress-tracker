package com.progresstracker.progresstracker.automation;

import com.progresstracker.progresstracker.repository.WeeklySummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Asks for each active user's weekly summary. Meant to run on a weekly schedule
 * (EventBridge -> Lambda -> this endpoint). It only records the requests; the worker writes the
 * summaries, with Claude when an API key is configured and from a template otherwise, so a slow
 * model call never holds up this request.
 */
@Service
public class WeeklySummaryService {

    private static final Logger log = LoggerFactory.getLogger(WeeklySummaryService.class);

    private final WeeklySummaryRepository weeklySummaryRepository;

    public WeeklySummaryService(WeeklySummaryRepository weeklySummaryRepository) {
        this.weeklySummaryRepository = weeklySummaryRepository;
    }

    /**
     * @param anyDayOfWeek any date in the week to summarize; weeks run Monday to Sunday
     * @return how many summaries were newly requested
     */
    @Transactional
    public int requestSummaries(LocalDate anyDayOfWeek) {
        LocalDate weekStart = startOfWeek(anyDayOfWeek);
        LocalDate weekEnd = weekStart.plusDays(6);

        int requested = weeklySummaryRepository.requestForActiveUsers(weekStart, weekEnd);
        log.info("WEEKLY_SUMMARY_REQUESTED count={} week={}..{}", requested, weekStart, weekEnd);
        return requested;
    }

    public static LocalDate startOfWeek(LocalDate anyDayOfWeek) {
        return anyDayOfWeek.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
