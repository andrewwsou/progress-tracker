package com.progresstracker.progresstracker.automation;

import com.progresstracker.progresstracker.repository.HabitRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The service passes the moment the job runs; the query works out each owner's local dates from
 * it. Which rows that selects, in every week position and time zone, is covered against a real
 * database in StreakResetIT.
 */
@ExtendWith(MockitoExtension.class)
class StreakResetServiceTest {

    @Mock
    private HabitRepository habitRepository;

    @Test
    void passesTheMomentTheJobRunsAndReturnsHowManyStreaksWereReset() {
        Instant now = Instant.parse("2026-07-16T07:00:00Z");
        when(habitRepository.resetLapsedStreaks(OffsetDateTime.of(2026, 7, 16, 7, 0, 0, 0, ZoneOffset.UTC))).thenReturn(7);

        assertThat(new StreakResetService(habitRepository).resetBrokenStreaks(now)).isEqualTo(7);

        verify(habitRepository).resetLapsedStreaks(now.atOffset(ZoneOffset.UTC));
    }
}
