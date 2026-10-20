package io.github.logicsatinn.dbscheduler.console.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.FailedExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryFilter;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class RetryLookupTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    JdbcTemplate jdbc;
    HistoryRepository history;
    RetryLookup lookup;

    @BeforeEach
    void setUp() {
        DataSource ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        jdbc = new JdbcTemplate(ds);
        history = new HistoryRepository(ds, Dialect.H2);
        lookup = new RetryLookup(new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2),
                new FailedExecutionRepository(ds, "scheduled_tasks", Dialect.H2), history,
                List.of(Tasks.oneTime("email").execute((i, c) -> { }),
                        Tasks.recurring("cleanup", FixedDelay.ofHours(1)).execute((i, c) -> { })));
    }

    void live(String task, String id, int failures, boolean picked) {
        jdbc.update("insert into scheduled_tasks (task_name, task_instance, execution_time, picked,"
                        + " consecutive_failures, version) values (?, ?, ?, ?, ?, 1)",
                task, id, Timestamp.from(NOW), picked, failures);
    }

    void record(String task, String id, HistoryEntry.Outcome outcome, String type) {
        history.insert(new HistoryEntry(0, task, id, outcome, NOW.minusSeconds(30),
                NOW.minusSeconds(29), 1000, null, null, null, "node-1", null, type));
    }

    Map<String, RetryLookup.Option> optionsByInstance() {
        List<HistoryEntry> entries = history.page(HistoryFilter.none(null, 100)).items();
        Map<Long, RetryLookup.Option> options = lookup.forHistory(entries);
        return entries.stream().filter(e -> options.containsKey(e.id()))
                .collect(Collectors.toMap(e -> e.taskName() + "/" + e.instanceId() + "#" + e.id(),
                        e -> options.get(e.id())));
    }

    @Test
    void offersRetryOrRerunOnlyOnTheLatestFailureOfEachInstance() {
        live("email", "retrying", 2, false);
        record("email", "retrying", HistoryEntry.Outcome.FAILED, "java.lang.Void");
        record("email", "retrying", HistoryEntry.Outcome.FAILED, "java.lang.Void");
        jdbc.update("insert into dsc_failed_execution (task_name, task_instance, priority,"
                + " failure_count, failed_at) values ('email', 'parked', 0, 4, ?)", Timestamp.from(NOW));
        record("email", "parked", HistoryEntry.Outcome.FAILED, null);
        record("email", "gone", HistoryEntry.Outcome.FAILED, "java.lang.Void");
        record("email", "unrecorded", HistoryEntry.Outcome.FAILED, null);
        record("email", "recovered", HistoryEntry.Outcome.FAILED, "java.lang.Void");
        record("email", "recovered", HistoryEntry.Outcome.SUCCEEDED, "java.lang.Void");
        live("email", "running", 1, true);
        record("email", "running", HistoryEntry.Outcome.FAILED, "java.lang.Void");
        record("cleanup", RecurringTask.INSTANCE, HistoryEntry.Outcome.FAILED, "java.lang.Void");
        record("unregistered", "x", HistoryEntry.Outcome.FAILED, "java.lang.Void");
        record("email", "succeeded", HistoryEntry.Outcome.SUCCEEDED, "java.lang.Void");

        var options = optionsByInstance();

        long latestRetrying = history.forInstance("email", "retrying", 1).get(0).id();
        long parked = history.forInstance("email", "parked", 1).get(0).id();
        long gone = history.forInstance("email", "gone", 1).get(0).id();
        assertThat(options).containsOnly(
                entry("email/retrying#" + latestRetrying, RetryLookup.Option.RETRY),
                entry("email/parked#" + parked, RetryLookup.Option.RETRY),
                entry("email/gone#" + gone, RetryLookup.Option.RERUN));
    }

    @Test
    void noFailuresNeedNoLookups() {
        record("email", "succeeded", HistoryEntry.Outcome.SUCCEEDED, "java.lang.Void");

        assertThat(lookup.forHistory(history.page(HistoryFilter.none(null, 10)).items())).isEmpty();
        assertThat(lookup.forHistory(List.of())).isEmpty();
    }
}
