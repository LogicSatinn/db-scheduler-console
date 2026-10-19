package io.github.logicsatinn.dbscheduler.console.data;

import static org.assertj.core.api.Assertions.*;
import io.github.logicsatinn.dbscheduler.console.data.history.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;

class CursorPaginationTest {
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    ExecutionRepository executions;
    HistoryRepository history;
    JdbcTemplate jdbc;
    @BeforeEach void setup() {
        var ds = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql").build();
        executions = new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2);
        history = new HistoryRepository(ds, Dialect.H2);
        jdbc = new JdbcTemplate(ds);
        for (int i = 0; i < 12; i++) {
            jdbc.update("INSERT INTO scheduled_tasks (task_name, task_instance, task_data, execution_time, picked, "
                    + "consecutive_failures, last_heartbeat, version) VALUES ('task', ?, ?, ?, false, ?, ?, 1)",
                    "id-" + i, new byte[100000], Timestamp.from(NOW.plusSeconds(i / 3)), i % 3,
                    i % 2 == 0 ? Timestamp.from(NOW.plusSeconds(i / 3)) : null);
            jdbc.update("INSERT INTO dsc_failed_execution (task_name, task_instance, task_data, failed_at, "
                    + "failure_count, priority) VALUES ('task', ?, ?, ?, ?, 0)",
                    "id-" + i, new byte[100000], Timestamp.from(NOW.plusSeconds(i / 3)), i % 3);
            history.insert(new HistoryEntry(0, "task", "id-" + i, HistoryEntry.Outcome.SUCCEEDED,
                    NOW.plusSeconds(i / 3), NOW.plusSeconds(i / 3 + 1), 1000,
                    null, null, null, null, new byte[100000], "bytes"));
        }
    }
    @Test void everySortTraversesMergedSourcesWithoutDuplicatesAndBackwardsRoundTrips() {
        for (SortColumn sort : SortColumn.values()) for (boolean desc : List.of(false, true)) {
            List<String> seen = new ArrayList<>();
            String cursor = null;
            Page<ExecutionRow> previous = null;
            int requests = 0;
            do {
                var page = executions.page(filter(cursor, sort, desc, 3), NOW, true);
                assertThat(page.items()).hasSizeLessThanOrEqualTo(3).allMatch(row -> row.taskData() == null);
                for (var row : page.items()) seen.add(row.instanceId() + ":" + row.parked());
                if (previous != null) {
                    var back = executions.page(filter(page.previousCursor(), sort, desc, 3), NOW, true);
                    assertThat(back.items()).isEqualTo(previous.items());
                }
                previous = page; cursor = page.nextCursor();
                assertThat(++requests).isLessThanOrEqualTo(8);
            } while (cursor != null);
            assertThat(seen).hasSize(24).doesNotHaveDuplicates();
        }
    }
    @Test void rejectsOversizedMalformedAndMismatchedCursors() {
        var first = executions.page(filter(null, SortColumn.EXECUTION_TIME, false, 3), NOW, true);
        assertThatThrownBy(() -> executions.page(filter("x".repeat(5000), SortColumn.EXECUTION_TIME, false, 3), NOW, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> executions.page(filter(first.nextCursor(), SortColumn.TASK_NAME, false, 3), NOW, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> executions.page(filter(null, SortColumn.TASK_NAME, false, 201), NOW, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void historyTraversesTiesWithoutLoadingBlobs() {
        List<Long> ids = new ArrayList<>(); String cursor = null;
        Page<HistoryEntry> previous = null;
        do {
            var page = history.page(HistoryFilter.none(cursor, 2));
            assertThat(page.items()).hasSize(2).allMatch(row -> row.taskData() == null);
            ids.addAll(page.items().stream().map(HistoryEntry::id).toList());
            if (previous != null)
                assertThat(history.page(HistoryFilter.none(page.previousCursor(), 2)).items()).isEqualTo(previous.items());
            previous = page; cursor = page.nextCursor();
            assertThat(ids.size()).isLessThanOrEqualTo(12);
        } while (cursor != null);
        assertThat(ids).hasSize(12).doesNotHaveDuplicates();
    }
    private ExecutionFilter filter(String cursor, SortColumn sort, boolean desc, int size) {
        return new ExecutionFilter(null, null, null, null, null, cursor, size, sort, desc);
    }
}
