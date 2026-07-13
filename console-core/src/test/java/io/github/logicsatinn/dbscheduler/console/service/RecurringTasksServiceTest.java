package io.github.logicsatinn.dbscheduler.console.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class RecurringTasksServiceTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    DataSource ds;
    JdbcTemplate jdbc;
    RecurringTasksService service;
    HistoryRepository history;

    static final List<Task<?>> KNOWN = List.of(
            Tasks.recurring("cleanup", FixedDelay.ofHours(1)).execute((i, c) -> {}),
            Tasks.oneTime("email-send").execute((i, c) -> {}));

    @BeforeEach
    void setUp() {
        ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        jdbc = new JdbcTemplate(ds);
        history = new HistoryRepository(ds, Dialect.H2);
        service = new RecurringTasksService(KNOWN,
                new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2), history);
    }

    @Test
    void rowsShowTypeNextRunAndLastOutcome() {
        jdbc.update("insert into scheduled_tasks (task_name, task_instance, execution_time, picked, version)"
                + " values ('cleanup', 'recurring', ?, false, 1)",
                Timestamp.from(NOW.plusSeconds(1800)));
        history.insert(new HistoryEntry(0, "cleanup", "recurring", HistoryEntry.Outcome.SUCCEEDED,
                NOW.minusSeconds(1800), NOW.minusSeconds(1798), 2000, null, null, null, "n1"));

        var rows = service.rows();
        assertThat(rows).hasSize(2);
        // sorted by name: cleanup, email-send
        assertThat(rows.get(0).taskName()).isEqualTo("cleanup");
        assertThat(rows.get(0).type()).isEqualTo("Recurring");
        assertThat(rows.get(0).nextExecution()).isEqualTo(NOW.plusSeconds(1800));
        assertThat(rows.get(0).lastRun().outcome()).isEqualTo(HistoryEntry.Outcome.SUCCEEDED);
        assertThat(rows.get(1).taskName()).isEqualTo("email-send");
        assertThat(rows.get(1).type()).isEqualTo("One-time");
        assertThat(rows.get(1).nextExecution()).isNull();
        assertThat(rows.get(1).lastRun()).isNull();
    }
}
