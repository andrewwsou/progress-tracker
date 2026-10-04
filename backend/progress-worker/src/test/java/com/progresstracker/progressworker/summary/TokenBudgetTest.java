package com.progresstracker.progressworker.summary;

import com.progresstracker.progressworker.repository.LlmDailyUsageRepository;
import com.progresstracker.progressworker.repository.WeeklySummaryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** How much each call reserves against the daily caps. */
@ExtendWith(MockitoExtension.class)
class TokenBudgetTest {

    @Mock
    private WeeklySummaryRepository summaries;

    @Mock
    private LlmDailyUsageRepository usage;

    private TokenBudget budget(int maxRetries, boolean refusalFallbacks) {
        return new TokenBudget(summaries, usage, new SummaryProperties(
                new SummaryProperties.Llm(true, "test-key", "claude-opus-5-5", "low", 2048, Duration.ofSeconds(30),
                        maxRetries, null, 100_000, refusalFallbacks, 10, 20, 8000),
                new SummaryProperties.Job(5, Duration.ofMinutes(5), 3)));
    }

    @Test
    void oneRequestReservesThePromptPlusTheOutputCapForTheModelAndItsFallback() {
        when(usage.reserve(eq(LocalDate.now(ZoneOffset.UTC)), anyInt(), anyLong(), anyInt(), anyLong())).thenReturn(1);

        assertThat(budget(0, true).tryReserve(500)).isTrue();

        // (500 prompt bytes + 1,000 overhead + 2,048 output) x 2 runs, against 10 calls and 100,000 tokens.
        verify(usage).reserve(LocalDate.now(ZoneOffset.UTC), 1, 7_096, 10, 100_000);
    }

    @Test
    void everyRetryIsReservedUpFront() {
        ArgumentCaptor<Integer> calls = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Long> tokens = ArgumentCaptor.forClass(Long.class);
        when(usage.reserve(eq(LocalDate.now(ZoneOffset.UTC)), calls.capture(), tokens.capture(), eq(10), eq(100_000L)))
                .thenReturn(1);

        budget(2, false).tryReserve(500);

        assertThat(calls.getValue()).isEqualTo(3);
        assertThat(tokens.getValue()).isEqualTo(3 * (500 + 1_000 + 2_048));
    }

    @Test
    void aFullDayRefusesTheCall() {
        when(usage.reserve(eq(LocalDate.now(ZoneOffset.UTC)), anyInt(), anyLong(), anyInt(), anyLong())).thenReturn(0);

        assertThat(budget(0, true).tryReserve(500)).isFalse();
    }
}
