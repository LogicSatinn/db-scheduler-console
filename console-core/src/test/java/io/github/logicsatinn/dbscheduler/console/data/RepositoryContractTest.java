package io.github.logicsatinn.dbscheduler.console.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryFilter;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
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

    /** Verifies the supported M1 upgrade before recreating the schema as a fresh M2 install. */
    static void prepareSchema(DataSource ds, Dialect dialect, String schedulerSchema) {
        applyScripts(ds, schedulerSchema,
                dialect.migrationResource().replace("/migrations/", "/m1/"));

        var jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO dsc_execution_history"
                        + " (task_name, task_instance, outcome, started_at, finished_at, duration_ms,"
                        + " exception_class, exception_message, stacktrace, picked_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "legacy-task", "legacy-instance", "SUCCEEDED",
                dialect.jdbcTimestamp(NOW.minusSeconds(2)),
                dialect.jdbcTimestamp(NOW.minusSeconds(1)), 1000,
                null, null, null, "legacy-node");

        applyScripts(ds, dialect.upgradeMigrationResource());

        var upgradedHistory = new HistoryRepository(ds, dialect);
        assertThat(upgradedHistory.schema()).isEqualTo(HistoryRepository.Schema.CURRENT);
        assertThat(upgradedHistory.forInstance("legacy-task", "legacy-instance", 1))
                .singleElement()
                .satisfies(entry -> assertThat(entry.taskData()).isNull());
        assertThat(new FailedExecutionRepository(ds, "scheduled_tasks", dialect).tableExists())
                .isTrue();

        upgradedHistory.insert(new HistoryEntry(0, "upgraded-task", "payload-instance",
                HistoryEntry.Outcome.SUCCEEDED, NOW.minusSeconds(1), NOW, 1000,
                null, null, null, "upgrade-node", new byte[] {9, 8, 7}, "example.PayloadV1"));
        assertThat(upgradedHistory.forInstance("upgraded-task", "payload-instance", 1))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.taskData()).containsExactly(9, 8, 7);
                    assertThat(entry.taskDataType()).isEqualTo("example.PayloadV1");
                });

        jdbc.execute("DROP TABLE dsc_failed_execution");
        jdbc.execute("DROP TABLE dsc_execution_history");
        applyScripts(ds, dialect.migrationResource());
    }

    JdbcTemplate jdbc;
    ExecutionRepository executions;
    FailedExecutionRepository failedExecutions;
    HistoryRepository history;

    @BeforeEach
    void cleanAndWire() {
        jdbc = new JdbcTemplate(dataSource());
        jdbc.update("delete from scheduled_tasks");
        jdbc.update("delete from dsc_execution_history");
        jdbc.update("delete from dsc_failed_execution");
        executions = new ExecutionRepository(dataSource(), "scheduled_tasks", dialect());
        failedExecutions = new FailedExecutionRepository(
                dataSource(), "scheduled_tasks", dialect());
        history = new HistoryRepository(dataSource(), dialect());
    }

    void insertExecution(String task, String id, Instant execTime, boolean picked, Integer failures) {
        jdbc.update("""
            insert into scheduled_tasks
              (task_name, task_instance, task_data, execution_time, picked, picked_by, consecutive_failures, version)
            values (?, ?, null, ?, ?, ?, ?, 1)""",
            task, id, dialect().jdbcTimestamp(execTime), picked,
            picked ? "node-1" : null, failures);
    }

    @Test
    void liveStateFiltersAndPagination() {
        insertExecution("t", "scheduled", NOW.plus(1, ChronoUnit.HOURS), false, 0);
        insertExecution("t", "due", NOW.minus(1, ChronoUnit.MINUTES), false, 0);
        insertExecution("t", "running", NOW, true, 0);
        insertExecution("t", "failing", NOW.plus(5, ChronoUnit.MINUTES), false, 2);

        for (ExecutionState state : java.util.List.of(ExecutionState.SCHEDULED,
                ExecutionState.DUE, ExecutionState.RUNNING, ExecutionState.RETRYING)) {
            var page = executions.page(new ExecutionFilter(state, null, null, null, null,
                    0, 10, SortColumn.EXECUTION_TIME, false), NOW);
            assertThat(page.items()).as("state %s", state).hasSize(1);
        }
        assertThat(executions.page(new ExecutionFilter(ExecutionState.FAILED, null, null,
                null, null, 0, 10, SortColumn.EXECUTION_TIME, false), NOW).items()).isEmpty();

        for (int i = 0; i < 12; i++) {
            insertExecution("bulk", "i-%02d".formatted(i), NOW.plus(i, ChronoUnit.HOURS), false, 0);
        }
        var page1 = executions.page(new ExecutionFilter(null, "bulk", null, null, null,
                1, 5, SortColumn.EXECUTION_TIME, false), NOW);
        assertThat(page1.total()).isEqualTo(12);
        assertThat(page1.items()).extracting(ExecutionRow::instanceId).first().isEqualTo("i-05");

        var counts = executions.counts(NOW);
        assertThat(counts.running()).isEqualTo(1);
        assertThat(counts.retrying()).isEqualTo(1);
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
                "java.lang.RuntimeException", "boom", "stack", "node-1",
                new byte[] {1, 2, 3}, "example.PayloadV1"));
        history.insert(new HistoryEntry(0, "email", "1", HistoryEntry.Outcome.SUCCEEDED,
                NOW.minus(20, ChronoUnit.DAYS), NOW.minus(20, ChronoUnit.DAYS).plusSeconds(1),
                1000, null, null, null, "node-1"));

        assertThat(history.tableExists()).isTrue();
        assertThat(history.page(new HistoryFilter(null, null, "boom", null, null, 0, 10)).items()).hasSize(1);
        assertThat(history.forInstance("email", "1", 10)).hasSize(2);
        assertThat(history.recentFailures(5)).hasSize(1);
        assertThat(history.countsSince(NOW.minus(1, ChronoUnit.DAYS)).failed()).isEqualTo(1);
        assertThat(history.window(NOW.minus(1, ChronoUnit.HOURS), NOW, 100)).hasSize(1);
        assertThat(history.forInstance("email", "1", 1).get(0).taskData())
                .containsExactly(new byte[] {1, 2, 3});
        assertThat(history.purgeOlderThan(NOW.minus(14, ChronoUnit.DAYS))).isEqualTo(1);
    }

    @Test
    void parkingAndRequeueAreTransactionalAndPreserveBinaryPayload() {
        byte[] payload = new byte[] {0, 7, -1, 42};
        insertExecution("portable", "failed", NOW, true, 3);
        jdbc.update("update scheduled_tasks set task_data = ?, priority = ?"
                + " where task_name = ? and task_instance = ?",
                payload, 6, "portable", "failed");
        var taskInstance = new TaskInstance<>("portable", "failed", payload);
        var execution = new Execution(NOW, taskInstance, true, "node-1",
                null, null, 3, NOW, 1);

        failedExecutions.park(ExecutionComplete.failure(execution, NOW, NOW.plusSeconds(1),
                new IllegalStateException("dialect failure")), byte[].class.getName());

        assertThat(executions.find("portable", "failed")).isEmpty();
        ExecutionRow parked = failedExecutions.find("portable", "failed").orElseThrow();
        assertThat(parked.taskData()).containsExactly(payload);
        assertThat(parked.priority()).isEqualTo(6);
        assertThat(parked.state(NOW)).isEqualTo(ExecutionState.FAILED);
        assertThat(failedExecutions.page(new ExecutionFilter(ExecutionState.FAILED,
                null, null, null, null, 0, 10, SortColumn.EXECUTION_TIME, false)).total())
                .isEqualTo(1);

        assertThat(failedExecutions.requeue(parked, NOW.plusSeconds(5))).isTrue();
        assertThat(failedExecutions.find("portable", "failed")).isEmpty();
        ExecutionRow requeued = executions.find("portable", "failed").orElseThrow();
        assertThat(requeued.taskData()).containsExactly(payload);
        assertThat(requeued.priority()).isEqualTo(6);
    }
}
