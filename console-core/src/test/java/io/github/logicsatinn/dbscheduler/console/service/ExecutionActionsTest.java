package io.github.logicsatinn.dbscheduler.console.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class ExecutionActionsTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");
    static final OneTimeTask<Void> TASK = Tasks.oneTime("action-task").execute((i, c) -> {});

    DataSource ds;
    JdbcTemplate jdbc;
    SchedulerClient client;
    ExecutionActions actions;
    ExecutionRepository repo;

    @BeforeEach
    void setUp() {
        ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .build();
        jdbc = new JdbcTemplate(ds);
        client = SchedulerClient.Builder.create(ds, TASK).build();
        actions = new ExecutionActions(client, Clock.fixed(NOW, ZoneOffset.UTC));
        repo = new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2);
    }

    void scheduleAt(String id, Instant when) {
        client.scheduleIfNotExists(TASK.instance(id), when);
    }

    @Test
    void runNowMovesExecutionTime() {
        scheduleAt("1", NOW.plus(2, ChronoUnit.HOURS));
        var result = actions.runNow("action-task", "1");
        assertThat(result.success()).isTrue();
        assertThat(repo.find("action-task", "1").orElseThrow().executionTime()).isEqualTo(NOW);
    }

    @Test
    void rescheduleSetsExactTime() {
        scheduleAt("1", NOW.plus(2, ChronoUnit.HOURS));
        Instant target = NOW.plus(1, ChronoUnit.DAYS);
        var result = actions.reschedule("action-task", "1", target);
        assertThat(result.success()).isTrue();
        assertThat(repo.find("action-task", "1").orElseThrow().executionTime()).isEqualTo(target);
    }

    @Test
    void deleteCancelsExecution() {
        scheduleAt("1", NOW.plus(2, ChronoUnit.HOURS));
        var result = actions.delete("action-task", "1");
        assertThat(result.success()).isTrue();
        assertThat(repo.find("action-task", "1")).isEmpty();
    }

    @Test
    void actionsOnPickedOrMissingExecutionsFailGracefully() {
        scheduleAt("picked", NOW.plus(1, ChronoUnit.HOURS));
        jdbc.update("update scheduled_tasks set picked = true, picked_by = 'node-1'"
                + " where task_instance = 'picked'");

        assertThat(actions.runNow("action-task", "picked").success()).isFalse();
        assertThat(actions.delete("action-task", "picked").success()).isFalse();
        assertThat(actions.runNow("action-task", "missing").success()).isFalse();
    }

    @Test
    void bulkAggregates() {
        scheduleAt("a", NOW.plus(1, ChronoUnit.HOURS));
        scheduleAt("b", NOW.plus(1, ChronoUnit.HOURS));
        var result = actions.bulk(ExecutionActions.Kind.RUN_NOW, List.of(
                new ExecutionActions.InstanceRef("action-task", "a"),
                new ExecutionActions.InstanceRef("action-task", "b"),
                new ExecutionActions.InstanceRef("action-task", "missing")));
        assertThat(result.success()).isFalse(); // at least one failure
        assertThat(result.message()).contains("2 succeeded").contains("1 failed");
    }

    @Test
    void instanceRefRoundTrip() {
        var ref = new ExecutionActions.InstanceRef("t", "i");
        assertThat(ExecutionActions.InstanceRef.parse(ref.composite())).isEqualTo(ref);
    }
}
