package com.progresstracker.progressworker.repository;

import com.progresstracker.progressworker.model.LlmDailyUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

public interface LlmDailyUsageRepository extends JpaRepository<LlmDailyUsage, LocalDate> {

    /**
     * Adds a call to the day's count, only if the result stays within both caps. One statement:
     * the conflict update locks the day's row and re-checks the caps, so two workers reserving at
     * the same moment cannot both squeeze past the limit. The first insert of the day checks the
     * caps too, so a cap of 0 stops every call.
     *
     * @return 1 if the call may go ahead, 0 if it would pass a cap
     */
    @Modifying
    @Query(value = """
            insert into llm_daily_usage (usage_date, calls, reserved_tokens)
            select cast(:day as date), :calls, :tokens
            where :calls <= :maxCalls and :tokens <= :maxTokens
            on conflict (usage_date) do update
               set calls = llm_daily_usage.calls + excluded.calls,
                   reserved_tokens = llm_daily_usage.reserved_tokens + excluded.reserved_tokens
             where llm_daily_usage.calls + excluded.calls <= :maxCalls
               and llm_daily_usage.reserved_tokens + excluded.reserved_tokens <= :maxTokens
            """, nativeQuery = true)
    int reserve(@Param("day") LocalDate day,
                @Param("calls") int calls,
                @Param("tokens") long tokens,
                @Param("maxCalls") int maxCalls,
                @Param("maxTokens") long maxTokens);
}
