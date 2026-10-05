package com.progresstracker.progresstracker.integration;

import com.progresstracker.progresstracker.model.Habit;
import com.progresstracker.progresstracker.repository.HabitRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Reproduces the race found under load: many requests completing the same habit at once all pass
 * the "already completed today?" check before any of them commits. The database's unique
 * constraint on (habit_id, completed_date) must let exactly one through, and every other request
 * must still get a normal response rather than a 500.
 *
 * This needs a real PostgreSQL: a mock cannot violate a constraint.
 */
@TestPropertySource(properties = "queue.enabled=false")
class HabitCompletionConcurrencyIT extends IntegrationTestBase {

    private static final int CONCURRENT_REQUESTS = 50;

    @Autowired
    private HabitRepository habitRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Test
    void concurrentCompletionsOfOneHabitAllSucceedAndRewardExactlyOnce() throws Exception {
        String token = registerUser(uniqueEmail());
        long habitId = createDailyHabit(token, "Read");

        List<Integer> statuses = completeConcurrently(token, habitId, CONCURRENT_REQUESTS);

        assertThat(statuses).hasSize(CONCURRENT_REQUESTS).containsOnly(200);
        assertThat(entryCount(habitId)).isEqualTo(1);

        Map<String, Object> habit = jdbc.queryForMap(
                "select xp_total, current_streak, longest_streak from habit where id = ?", habitId);
        assertThat(habit.get("xp_total")).isEqualTo(10);
        assertThat(habit.get("current_streak")).isEqualTo(1);
        assertThat(habit.get("longest_streak")).isEqualTo(1);
    }

    @Test
    void theTestClientDoesNotQueueConcurrentRequests() {
        // Guards every concurrency test here: a pooled client (5 connections per host by default)
        // would let them pass while sending their requests a few at a time.
        assertThat(rest.getRestTemplate().getRequestFactory()).isInstanceOf(JdkClientHttpRequestFactory.class);
    }

    @Test
    void aHabitReportsWhetherItIsAlreadyDoneForTodayByTheServersCalendar() {
        String token = registerUser(uniqueEmail());
        long habitId = createDailyHabit(token, "Read");
        assertThat(send(HttpMethod.GET, "/api/habits", token, null).getBody().get(0).get("completedForPeriod").asBoolean())
                .isFalse();

        assertThat(completeHabit(token, habitId).getBody().get("completedForPeriod").asBoolean()).isTrue();
        assertThat(send(HttpMethod.GET, "/api/habits", token, null).getBody().get(0).get("completedForPeriod").asBoolean())
                .isTrue();
    }

    @Test
    void completingTheSameHabitAgainTheSameDayChangesNothing() {
        String token = registerUser(uniqueEmail());
        long habitId = createDailyHabit(token, "Stretch");

        assertThat(completeHabit(token, habitId).getStatusCode().value()).isEqualTo(200);
        assertThat(completeHabit(token, habitId).getStatusCode().value()).isEqualTo(200);

        assertThat(entryCount(habitId)).isEqualTo(1);
        assertThat(xpTotal(habitId)).isEqualTo(10);
    }

    /**
     * A different race: a brand-new user completing several habits at once. Every completion tries
     * to unlock the same first-completion achievement. The unlock is an insert that does nothing on
     * conflict, so all of them succeed and the achievement is unlocked exactly once.
     */
    @Test
    void concurrentCompletionsOfDifferentHabitsAllSucceedAndUnlockTheAchievementOnce() throws Exception {
        String email = uniqueEmail();
        String token = registerUser(email);
        List<Long> habitIds = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            habitIds.add(createDailyHabit(token, "Habit " + i));
        }

        List<Callable<Integer>> calls = new ArrayList<>();
        for (long habitId : habitIds) {
            calls.add(() -> completeHabit(token, habitId).getStatusCode().value());
        }
        List<Integer> statuses = runAtOnce(calls);

        assertThat(statuses).hasSize(habitIds.size()).containsOnly(200);
        for (long habitId : habitIds) {
            assertThat(entryCount(habitId)).isEqualTo(1);
            assertThat(xpTotal(habitId)).isEqualTo(10);
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from user_achievement where user_id = ?", Integer.class, userIdFor(email)))
                .isEqualTo(1);
    }

    /**
     * Two habits of one user, with 85 XP between them, are completed at once. Each completion adds
     * 10, so the user ends at 105 and must unlock the 100 XP achievement. Without the user's row
     * lock both completions summed the XP before either committed, each saw 95, and neither unlocked it.
     *
     * A second connection holds both of today's entries uncommitted, so both completions are under
     * way before either can finish. Then it lets them go at the same moment.
     */
    @Test
    void concurrentCompletionsOfOneUsersHabitsSeeEachOthersXp() throws Exception {
        String email = uniqueEmail();
        String token = registerUser(email);
        long first = createDailyHabit(token, "First");
        long second = createDailyHabit(token, "Second");
        jdbc.update("update habit set xp_total = 40 where id = ?", first);
        jdbc.update("update habit set xp_total = 45 where id = ?", second);
        // The first-completion achievement is already unlocked, so the only one at stake is XP_100.
        jdbc.update("insert into user_achievement (user_id, achievement_id, unlocked_at) "
                + "select ?, id, now() from achievement where code = 'FIRST_COMPLETION'", userIdFor(email));

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement entry = holder.prepareStatement(
                    "insert into habit_entries (habit_id, completed_date, xp_earned, created_at) values (?, ?, 0, now())")) {
                for (long habitId : List.of(first, second)) {
                    entry.setLong(1, habitId);
                    entry.setObject(2, LocalDate.now(ZoneOffset.UTC));
                    entry.executeUpdate();
                }
            }

            Future<Integer> completeFirst = threads.submit(() -> completeHabit(token, first).getStatusCode().value());
            Future<Integer> completeSecond = threads.submit(() -> completeHabit(token, second).getStatusCode().value());
            // Both are waiting: on the held entries, or (with the user lock) one of them on the other.
            await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
                    "select count(*) from pg_stat_activity where wait_event_type = 'Lock' "
                            + "and (query ilike '%insert into habit_entries%' or query ilike '%from app_user%for no key update%')",
                    Integer.class) >= 2);

            holder.rollback();
            assertThat(completeFirst.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(completeSecond.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            threads.shutdownNow();
        }

        assertThat(xpTotal(first) + xpTotal(second)).isEqualTo(105);
        assertThat(jdbc.queryForObject("select count(*) from user_achievement ua join achievement a on a.id = ua.achievement_id "
                + "where ua.user_id = ? and a.code = 'XP_100'", Integer.class, userIdFor(email))).isEqualTo(1);
    }

    /**
     * The API and the worker both update the habit row: one the name and goal, the other XP and
     * streaks. An edit must write only what it changed, or it would put back the XP it loaded
     * before the worker's reward landed.
     */
    @Test
    void editingAHabitDoesNotOverwriteARewardAppliedInTheMeantime() {
        String token = registerUser(uniqueEmail());
        long habitId = createDailyHabit(token, "Before");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Habit habit = habitRepository.findById(habitId).orElseThrow();
            // After the edit loaded the row, the reward is written by someone else.
            jdbc.update("update habit set xp_total = 10, current_streak = 1 where id = ?", habitId);
            habit.setName("After");
        });

        Map<String, Object> row = jdbc.queryForMap(
                "select name, xp_total, current_streak from habit where id = ?", habitId);
        assertThat(row.get("name")).isEqualTo("After");
        assertThat(row.get("xp_total")).isEqualTo(10);
        assertThat(row.get("current_streak")).isEqualTo(1);
    }
}
