package io.github.logicsatinn.dbscheduler.console.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class ExecutionRepositoryTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    DataSource ds;
    JdbcTemplate jdbc;
    ExecutionRepository repo;

    @BeforeEach
    void setUp() {
        ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .build();
        jdbc = new JdbcTemplate(ds);
        repo = new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2);
    }

    void insert(String task, String id, Instant execTime, boolean picked, String pickedBy, Integer failures) {
        jdbc.update("""
            insert into scheduled_tasks
              (task_name, task_instance, task_data, execution_time, picked, picked_by, consecutive_failures, version)
            values (?, ?, null, ?, ?, ?, ?, 1)""",
            task, id, Timestamp.from(execTime), picked, pickedBy, failures);
    }

    @Test
    void filtersByDerivedState() {
        insert("task-a", "1", NOW.plus(1, ChronoUnit.HOURS), false, null, 0);   // SCHEDULED
        insert("task-a", "2", NOW.minus(1, ChronoUnit.MINUTES), false, null, 0); // DUE
        insert("task-b", "3", NOW.minus(1, ChronoUnit.MINUTES), true, "node-1", 0); // RUNNING
        insert("task-b", "4", NOW.plus(5, ChronoUnit.MINUTES), false, null, 3);  // FAILING

        for (var expected : List.of(
                java.util.Map.entry(ExecutionState.SCHEDULED, "1"),
                java.util.Map.entry(ExecutionState.DUE, "2"),
                java.util.Map.entry(ExecutionState.RUNNING, "3"),
                java.util.Map.entry(ExecutionState.FAILING, "4"))) {
            var filter = new ExecutionFilter(expected.getKey(), null, null, null, null,
                    0, 10, SortColumn.EXECUTION_TIME, false);
            Page<ExecutionRow> page = repo.page(filter, NOW);
            assertThat(page.items()).extracting(ExecutionRow::instanceId)
                    .as("state %s", expected.getKey())
                    .containsExactly(expected.getValue());
        }
    }

    @Test
    void filtersByTaskNameAndInstanceSearchAndTimeRange() {
        insert("email-send", "order-100", NOW.plus(10, ChronoUnit.MINUTES), false, null, 0);
        insert("email-send", "order-200", NOW.plus(20, ChronoUnit.MINUTES), false, null, 0);
        insert("report-gen", "order-100", NOW.plus(30, ChronoUnit.MINUTES), false, null, 0);

        var byTask = repo.page(new ExecutionFilter(null, "email-send", null, null, null,
                0, 10, SortColumn.EXECUTION_TIME, false), NOW);
        assertThat(byTask.total()).isEqualTo(2);

        var bySearch = repo.page(new ExecutionFilter(null, null, "ORDER-1", null, null,
                0, 10, SortColumn.EXECUTION_TIME, false), NOW);
        assertThat(bySearch.items()).extracting(ExecutionRow::taskName)
                .containsExactlyInAnyOrder("email-send", "report-gen");

        var byRange = repo.page(new ExecutionFilter(null, null, null,
                NOW.plus(15, ChronoUnit.MINUTES), NOW.plus(25, ChronoUnit.MINUTES),
                0, 10, SortColumn.EXECUTION_TIME, false), NOW);
        assertThat(byRange.items()).extracting(ExecutionRow::instanceId).containsExactly("order-200");
    }

    @Test
    void paginatesAndSorts() {
        for (int i = 0; i < 25; i++) {
            insert("bulk", "i-%02d".formatted(i), NOW.plus(i, ChronoUnit.MINUTES), false, null, 0);
        }
        var page0 = repo.page(ExecutionFilter.none(0, 10), NOW);
        var page2 = repo.page(ExecutionFilter.none(2, 10), NOW);
        assertThat(page0.total()).isEqualTo(25);
        assertThat(page0.totalPages()).isEqualTo(3);
        assertThat(page0.items()).hasSize(10);
        assertThat(page2.items()).hasSize(5);
        assertThat(page0.items().get(0).instanceId()).isEqualTo("i-00");

        var desc = repo.page(new ExecutionFilter(null, null, null, null, null,
                0, 10, SortColumn.EXECUTION_TIME, true), NOW);
        assertThat(desc.items().get(0).instanceId()).isEqualTo("i-24");
    }

    @Test
    void findCountsAndDistinctNames() {
        insert("task-a", "1", NOW.plus(1, ChronoUnit.HOURS), false, null, 0);
        insert("task-a", "2", NOW.minus(1, ChronoUnit.MINUTES), false, null, 0);
        insert("task-b", "3", NOW, true, "node-1", 0);
        insert("task-b", "4", NOW, false, null, 2);

        assertThat(repo.find("task-a", "1")).isPresent();
        assertThat(repo.find("task-a", "missing")).isEmpty();
        assertThat(repo.distinctTaskNames()).containsExactly("task-a", "task-b");

        var counts = repo.counts(NOW);
        assertThat(counts.scheduled()).isEqualTo(1);
        assertThat(counts.due()).isEqualTo(1);
        assertThat(counts.running()).isEqualTo(1);
        assertThat(counts.failing()).isEqualTo(1);
    }
}
