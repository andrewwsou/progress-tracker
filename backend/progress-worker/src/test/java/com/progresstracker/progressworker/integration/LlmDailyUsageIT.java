package com.progresstracker.progressworker.integration;

import com.progresstracker.progressworker.repository.LlmDailyUsageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The statement behind the hard daily caps on model calls, run against PostgreSQL with caps small
 * enough to reach. The summary tests only ever come near the call cap.
 */
class LlmDailyUsageIT extends WorkerIntegrationTestBase {

    // Days far from today, so the summary tests (which use today's row) never see these rows.
    private static final LocalDate DAY = LocalDate.of(2000, 1, 1);

    @Autowired
    private LlmDailyUsageRepository usage;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    @AfterEach
    void removeTestDays() {
        jdbc.update("delete from llm_daily_usage where usage_date between ? and ?", DAY, DAY.plusDays(9));
    }

    @Test
    void theTokenCapRefusesACallOnceTheDayHasReservedTooMuch() {
        assertThat(reserve(DAY, 1, 600, 10, 1_000)).isEqualTo(1);

        // Well within the call cap, but it would take the day to 1,200 tokens.
        assertThat(reserve(DAY, 1, 600, 10, 1_000)).isZero();
        assertThat(row(DAY)).containsExactly(1, 600L);

        // Reaching the cap exactly is allowed.
        assertThat(reserve(DAY, 1, 400, 10, 1_000)).isEqualTo(1);
        assertThat(row(DAY)).containsExactly(2, 1_000L);
    }

    @Test
    void theTokenCapAlsoAppliesToTheFirstCallOfTheDay() {
        assertThat(reserve(DAY, 1, 1_001, 10, 1_000)).isZero();

        assertThat(rowExists(DAY)).isFalse();
    }

    @Test
    void aCallCapOfZeroRefusesEveryCall() {
        // A day with no row yet: nothing is inserted.
        assertThat(reserve(DAY, 1, 1, 0, 1_000)).isZero();
        assertThat(rowExists(DAY)).isFalse();

        // A day that already has calls, after the cap is lowered to 0: nothing is added.
        LocalDate busyDay = DAY.plusDays(1);
        assertThat(reserve(busyDay, 1, 1, 10, 1_000)).isEqualTo(1);
        assertThat(reserve(busyDay, 1, 1, 0, 1_000)).isZero();
        assertThat(row(busyDay)).containsExactly(1, 1L);
    }

    // --- helpers ---------------------------------------------------------------------------

    /** The repository's modifying query needs a transaction, as TokenBudget gives it. */
    private int reserve(LocalDate day, int calls, long tokens, int maxCalls, long maxTokens) {
        Integer reserved = new TransactionTemplate(transactionManager)
                .execute(status -> usage.reserve(day, calls, tokens, maxCalls, maxTokens));
        return reserved == null ? 0 : reserved;
    }

    private List<Object> row(LocalDate day) {
        Map<String, Object> row = jdbc.queryForMap(
                "select calls, reserved_tokens from llm_daily_usage where usage_date = ?", day);
        return List.of(row.get("calls"), row.get("reserved_tokens"));
    }

    private boolean rowExists(LocalDate day) {
        return jdbc.queryForObject("select count(*) from llm_daily_usage where usage_date = ?", Integer.class, day) > 0;
    }
}
