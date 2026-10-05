package com.progresstracker.progresstracker.integration;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The habit list the dashboard reloads after every action: its order, its cost, and its size. */
@TestPropertySource(properties = "queue.enabled=false")
class HabitListIT extends IntegrationTestBase {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void habitsStayInTheOrderTheyWereCreated() {
        String token = registerUser(uniqueEmail());
        long a = createDailyHabit(token, "A");
        long b = createDailyHabit(token, "B");
        long c = createDailyHabit(token, "C");

        completeHabit(token, a);
        assertThat(habitIds(token)).containsExactly(a, b, c);

        // An update writes a new version of the row, which a scan in no particular order returns last.
        send(HttpMethod.PUT, "/api/habits/" + a, token, Map.of("name", "A, renamed", "frequency", "DAILY"));
        assertThat(habitIds(token)).containsExactly(a, b, c);
    }

    /**
     * Rows stored in the opposite order to their ids. Without an explicit order PostgreSQL returns
     * them as stored, whether it scans the table or the user_id index, so this pins the order down
     * where the test above depends on how the planner happens to read the table.
     */
    @Test
    void theListIsInIdOrderWhateverOrderTheRowsAreStoredIn() {
        String email = uniqueEmail();
        String token = registerUser(email);
        long userId = userIdFor(email);
        // Far above anything the identity sequence hands out in a test run.
        long base = 2_000_000_000L + userId * 10;
        for (long id = base + 2; id >= base; id--) {
            jdbc.update("insert into habit (id, user_id, name, description, frequency, goal_period, goal_target_count, "
                    + "xp_total, current_streak, longest_streak, created_at) "
                    + "values (?, ?, 'Habit', '', 'DAILY', 'DAILY', 1, 0, 0, 0, now())", id, userId);
        }

        assertThat(habitIds(token)).containsExactly(base, base + 1, base + 2);
    }

    @Test
    void theListCostsTheSameNumberOfQueriesHoweverManyHabitsThereAre() {
        String token = registerUser(uniqueEmail());
        long first = createDailyHabit(token, "First");
        completeHabit(token, first);

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            long withOne = statementsToList(token, statistics);
            for (int i = 0; i < 9; i++) {
                long habit = createDailyHabit(token, "Habit " + i);
                if (i % 2 == 0) {
                    completeHabit(token, habit);
                }
            }
            long withTen = statementsToList(token, statistics);

            assertThat(withOne).isPositive(); // the statistics are really being counted
            assertThat(withTen).isEqualTo(withOne);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    void anAccountCannotHaveMoreThanAHundredHabits() {
        String email = uniqueEmail();
        String token = registerUser(email);
        long userId = userIdFor(email);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            rows.add(new Object[]{userId, "Habit " + i});
        }
        jdbc.batchUpdate("insert into habit (user_id, name, description, frequency, goal_period, goal_target_count, "
                + "xp_total, current_streak, longest_streak, created_at) "
                + "values (?, ?, '', 'DAILY', 'DAILY', 1, 0, 0, 0, now())", rows);

        ResponseEntity<JsonNode> response = send(HttpMethod.POST, "/api/habits", token,
                Map.of("name", "One too many", "frequency", "DAILY"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().get("detail").asString()).isEqualTo("You can have at most 100 habits.");
        assertThat(jdbc.queryForObject("select count(*) from habit where user_id = ?", Integer.class, userId))
                .isEqualTo(100);
    }

    private long statementsToList(String token, Statistics statistics) {
        statistics.clear();
        ResponseEntity<JsonNode> response = send(HttpMethod.GET, "/api/habits", token, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return statistics.getPrepareStatementCount();
    }

    private List<Long> habitIds(String token) {
        List<Long> ids = new ArrayList<>();
        send(HttpMethod.GET, "/api/habits", token, null).getBody().forEach(habit -> ids.add(habit.get("id").asLong()));
        return ids;
    }
}
