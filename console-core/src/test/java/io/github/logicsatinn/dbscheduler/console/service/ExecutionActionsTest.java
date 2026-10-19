package io.github.logicsatinn.dbscheduler.console.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.NonAutoCommitDataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

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
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("2 succeeded").contains("1 failed");
    }

    @Test
    void actionsCommitWhenConnectionsDisableAutoCommit() {
        DataSource nonAutoCommit = new NonAutoCommitDataSource(ds);
        SchedulerClient bootClient = SchedulerClient.Builder.create(
                new TransactionAwareDataSourceProxy(nonAutoCommit), TASK).build();
        var bootActions = new ExecutionActions(bootClient, Clock.fixed(NOW, ZoneOffset.UTC),
                null, Serializer.DEFAULT_JAVA_SERIALIZER, List.<Task<?>>of(TASK),
                new TransactionTemplate(new DataSourceTransactionManager(nonAutoCommit)));
        scheduleAt("run", NOW.plus(2, ChronoUnit.HOURS));
        scheduleAt("move", NOW.plus(2, ChronoUnit.HOURS));
        scheduleAt("drop", NOW.plus(2, ChronoUnit.HOURS));
        Instant target = NOW.plus(1, ChronoUnit.DAYS);

        assertThat(bootActions.runNow("action-task", "run").success()).isTrue();
        assertThat(bootActions.reschedule("action-task", "move", target).success()).isTrue();
        assertThat(bootActions.delete("action-task", "drop").success()).isTrue();

        assertThat(repo.find("action-task", "run").orElseThrow().executionTime()).isEqualTo(NOW);
        assertThat(repo.find("action-task", "move").orElseThrow().executionTime()).isEqualTo(target);
        assertThat(repo.find("action-task", "drop")).isEmpty();
    }

    @Test
    void instanceRefRoundTrip() {
        var ref = new ExecutionActions.InstanceRef("t", "i");
        assertThat(ExecutionActions.InstanceRef.parse(ref.composite())).isEqualTo(ref);
    }
    @Test
    void unexpectedErrorsDoNotExposeExceptionMessages() {
        var broken = org.mockito.Mockito.mock(SchedulerClient.class);
        org.mockito.Mockito.when(broken.reschedule(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("password=secret SQL internals"));
        var result = new ExecutionActions(broken, Clock.fixed(NOW, ZoneOffset.UTC)).runNow("task", "id");
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Reference:").doesNotContain("secret", "SQL", "password");
    }

}
