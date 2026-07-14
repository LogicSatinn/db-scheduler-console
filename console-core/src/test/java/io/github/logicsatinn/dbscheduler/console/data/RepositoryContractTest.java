package io.github.logicsatinn.dbscheduler.console.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryFilter;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Contract every dialect must satisfy. Subclasses supply a live container. */
public abstract class RepositoryContractTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    protected abstract DataSource dataSource();

    protected abstract Dialect dialect();

    static DataSource simpleDataSource(String url, String user, String password) {
        return new DriverManagerDataSource(url, user, password);
    }

    static void applyScripts(DataSource ds, String... classpathScripts) {
        var populator = new ResourceDatabasePopulator();
        for (String s : classpathScripts) {
            populator.addScript(new ClassPathResource(s));
        }
        populator.execute(ds);
    }

    JdbcTemplate jdbc;
    ExecutionRepository executions;
    HistoryRepository history;

    @BeforeEach
    void cleanAndWire() {
        jdbc = new JdbcTemplate(dataSource());
        jdbc.update("delete from scheduled_tasks");
        jdbc.update("delete from dsc_execution_history");
        executions = new ExecutionRepository(dataSource(), "scheduled_tasks", dialect());
        history = new HistoryRepository(dataSource(), dialect());
    }

    void insertExecution(String task, String id, Instant execTime, boolean picked, Integer failures) {
        jdbc.update("""
            insert into scheduled_tasks
              (task_name, task_instance, task_data, execution_time, picked, picked_by, consecutive_failures, version)
            values (?, ?, null, ?, ?, ?, ?, 1)""",
            task, id, Timestamp.from(execTime), picked, picked ? "node-1" : null, failures);
    }

    @Test
    void liveStateFiltersAndPagination() {
        insertExecution("t", "scheduled", NOW.plus(1, ChronoUnit.HOURS), false, 0);
        insertExecution("t", "due", NOW.minus(1, ChronoUnit.MINUTES), false, 0);
        insertExecution("t", "running", NOW, true, 0);
        insertExecution("t", "failing", NOW.plus(5, ChronoUnit.MINUTES), false, 2);

        for (ExecutionState state : ExecutionState.values()) {
            var page = executions.page(new ExecutionFilter(state, null, null, null, null,
                    0, 10, SortColumn.EXECUTION_TIME, false), NOW);
            assertThat(page.items()).as("state %s", state).hasSize(1);
        }

        for (int i = 0; i < 12; i++) {
            insertExecution("bulk", "i-%02d".formatted(i), NOW.plus(i, ChronoUnit.HOURS), false, 0);
        }
        var page1 = executions.page(new ExecutionFilter(null, "bulk", null, null, null,
                1, 5, SortColumn.EXECUTION_TIME, false), NOW);
        assertThat(page1.total()).isEqualTo(12);
        assertThat(page1.items()).extracting(ExecutionRow::instanceId).first().isEqualTo("i-05");

        var counts = executions.counts(NOW);
        assertThat(counts.running()).isEqualTo(1);
        assertThat(counts.failing()).isEqualTo(1);
    }

    @Test
    void executionLookupsAndBatchedNextRuns() {
        insertExecution("email", "order-1", NOW.plus(2, ChronoUnit.HOURS), false, 0);
        insertExecution("email", "order-2", NOW.plus(1, ChronoUnit.HOURS), false, 0);
        insertExecution("report", "r-1", NOW.plus(30, ChronoUnit.MINUTES), false, 0);
        insertExecution("picked-only", "p-1", NOW, true, 0);

        assertThat(executions.find("email", "order-1")).isPresent();
        assertThat(executions.find("email", "nope")).isEmpty();
        assertThat(executions.distinctTaskNames())
                .containsExactly("email", "picked-only", "report");

        assertThat(executions.nextExecutionTime("email"))
                .contains(NOW.plus(1, ChronoUnit.HOURS));
        assertThat(executions.nextExecutionTime("picked-only")).isEmpty();

        assertThat(executions.nextExecutionTimes()).containsOnly(
                entry("email", NOW.plus(1, ChronoUnit.HOURS)),
                entry("report", NOW.plus(30, ChronoUnit.MINUTES)));
    }

    @Test
    void historyLatestPerTask() {
        history.insert(new HistoryEntry(0, "email", "1", HistoryEntry.Outcome.FAILED,
                NOW.minusSeconds(90), NOW.minusSeconds(88), 2000,
                "java.lang.RuntimeException", "older", "stack", "node-1"));
        history.insert(new HistoryEntry(0, "email", "2", HistoryEntry.Outcome.SUCCEEDED,
                NOW.minusSeconds(30), NOW.minusSeconds(29), 1000, null, null, null, "node-1"));
        history.insert(new HistoryEntry(0, "report", "3", HistoryEntry.Outcome.SUCCEEDED,
                NOW.minusSeconds(10), NOW.minusSeconds(9), 1000, null, null, null, "node-1"));

        assertThat(history.latestForTask("email")).get()
                .extracting(HistoryEntry::instanceId).isEqualTo("2");
        assertThat(history.latestForTask("absent")).isEmpty();

        var latest = history.latestPerTask();
        assertThat(latest).containsOnlyKeys("email", "report");
        assertThat(latest.get("email").instanceId()).isEqualTo("2");
        assertThat(latest.get("report").instanceId()).isEqualTo("3");
    }

    @Test
    void historyRoundTrip() {
        history.insert(new HistoryEntry(0, "email", "1", HistoryEntry.Outcome.FAILED,
                NOW.minusSeconds(30), NOW.minusSeconds(28), 2000,
                "java.lang.RuntimeException", "boom", "stack", "node-1"));
        history.insert(new HistoryEntry(0, "email", "1", HistoryEntry.Outcome.SUCCEEDED,
                NOW.minus(20, ChronoUnit.DAYS), NOW.minus(20, ChronoUnit.DAYS).plusSeconds(1),
                1000, null, null, null, "node-1"));

        assertThat(history.tableExists()).isTrue();
        assertThat(history.page(new HistoryFilter(null, null, "boom", null, null, 0, 10)).items()).hasSize(1);
        assertThat(history.forInstance("email", "1", 10)).hasSize(2);
        assertThat(history.recentFailures(5)).hasSize(1);
        assertThat(history.countsSince(NOW.minus(1, ChronoUnit.DAYS)).failed()).isEqualTo(1);
        assertThat(history.window(NOW.minus(1, ChronoUnit.HOURS), NOW, 100)).hasSize(1);
        assertThat(history.purgeOlderThan(NOW.minus(14, ChronoUnit.DAYS))).isEqualTo(1);
    }
}
