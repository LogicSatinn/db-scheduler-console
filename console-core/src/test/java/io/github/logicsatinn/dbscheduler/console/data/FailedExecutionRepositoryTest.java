package io.github.logicsatinn.dbscheduler.console.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import io.github.logicsatinn.dbscheduler.console.service.ExecutionActions;
import io.github.logicsatinn.dbscheduler.console.service.FailedExecutionParking;
import java.io.Serializable;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class FailedExecutionRepositoryTest {

    static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");
    static final Serializer SERIALIZER = Serializer.DEFAULT_JAVA_SERIALIZER;
    static final OneTimeTask<Payload> TASK = Tasks.oneTime("payment", Payload.class)
            .execute((instance, context) -> { });

    DataSource dataSource;
    JdbcTemplate jdbc;
    FailedExecutionRepository failed;
    SchedulerClient client;

    @BeforeEach
    void setUp() {
        dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        jdbc = new JdbcTemplate(dataSource);
        failed = new FailedExecutionRepository(dataSource, "scheduled_tasks", Dialect.H2);
        client = SchedulerClient.Builder.create(dataSource, TASK)
                .serializer(SERIALIZER)
                .enablePriority()
                .build();
    }

    @Test
    void parksAndRequeuesTheSamePayloadInstanceAndPriority() {
        Payload payload = new Payload("txn-42");
        var taskInstance = TASK.instanceBuilder("run-1").data(payload).priority(7).build();
        client.scheduleIfNotExists(taskInstance, NOW);
        jdbc.update("update scheduled_tasks set picked = true, picked_by = 'node-a',"
                + " version = version + 1 where task_name = 'payment' and task_instance = 'run-1'");

        Execution execution = new Execution(NOW, taskInstance, true, "node-a",
                null, NOW.minusSeconds(1), 3, NOW, 2);
        ExecutionComplete complete = ExecutionComplete.failure(
                execution, NOW, NOW.plusSeconds(1), new IllegalStateException("gateway down"));
        FailureHandler<Payload> handler = new FailedExecutionParking(failed).failureHandler();
        handler.onFailure(complete, null);

        assertThat(jdbc.queryForObject("select count(*) from scheduled_tasks", Long.class)).isZero();
        ExecutionRow parked = failed.find("payment", "run-1").orElseThrow();
        assertThat(parked.state(NOW)).isEqualTo(ExecutionState.FAILED);
        assertThat(parked.priority()).isEqualTo(7);
        assertThat(parked.consecutiveFailures()).isEqualTo(4);
        assertThat(parked.taskDataType()).isEqualTo(Payload.class.getName());
        assertThat(parked.exceptionMessage()).isEqualTo("gateway down");
        assertThat(SERIALIZER.deserialize(Payload.class, parked.taskData())).isEqualTo(payload);

        var actions = new ExecutionActions(client, Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC),
                failed, SERIALIZER, List.of(TASK));
        assertThat(actions.runNow("payment", "run-1").success()).isTrue();
        assertThat(failed.find("payment", "run-1")).isEmpty();

        var live = new ExecutionRepository(dataSource, "scheduled_tasks", Dialect.H2)
                .find("payment", "run-1").orElseThrow();
        assertThat(live.executionTime()).isEqualTo(NOW.plusSeconds(5));
        assertThat(live.priority()).isEqualTo(7);
        assertThat(SERIALIZER.deserialize(Payload.class, live.taskData())).isEqualTo(payload);
    }

    @Test
    void parkingCanBeCreatedDirectlyFromASupportedDataSource() {
        assertThat(new FailedExecutionParking(dataSource).failureHandler()).isNotNull();
        assertThat(new FailedExecutionParking(dataSource, "scheduled_tasks").failureHandler())
                .isNotNull();
    }

    @Test
    void parkingFailureLeavesTheLiveRowAuthoritative() {
        DataSource withoutParkingTable = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .build();
        JdbcTemplate liveJdbc = new JdbcTemplate(withoutParkingTable);
        liveJdbc.update("insert into scheduled_tasks"
                + " (task_name, task_instance, task_data, execution_time, picked, picked_by,"
                + " consecutive_failures, version, priority) values (?, ?, ?, ?, true, ?, 3, 2, 0)",
                "payment", "run-2", SERIALIZER.serialize(new Payload("txn-2")), NOW, "node-a");
        var repository = new FailedExecutionRepository(
                withoutParkingTable, "scheduled_tasks", Dialect.H2);
        var execution = new Execution(NOW, TASK.instance("run-2", new Payload("txn-2")),
                true, "node-a", null, null, 3, NOW, 2);

        assertThatThrownBy(() -> repository.park(ExecutionComplete.failure(
                execution, NOW, NOW.plusSeconds(1), new RuntimeException("boom")),
                Payload.class.getName())).isInstanceOf(RuntimeException.class);
        assertThat(liveJdbc.queryForObject("select count(*) from scheduled_tasks", Long.class))
                .isEqualTo(1);
    }

    @Test
    void onlyOneParkingAttemptCanWinForAnExecutionVersion() {
        Payload payload = new Payload("txn-race");
        var taskInstance = TASK.instance("run-race", payload);
        client.scheduleIfNotExists(taskInstance, NOW);
        jdbc.update("update scheduled_tasks set picked = true, picked_by = 'node-a',"
                + " version = version + 1 where task_name = 'payment' and task_instance = 'run-race'");
        var execution = new Execution(NOW, taskInstance, true, "node-a",
                null, null, 3, NOW, 2);
        var complete = ExecutionComplete.failure(
                execution, NOW, NOW.plusSeconds(1), new RuntimeException("exhausted"));

        failed.park(complete, Payload.class.getName());

        assertThatThrownBy(() -> failed.park(complete, Payload.class.getName()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(failed.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from scheduled_tasks", Long.class))
                .isZero();
    }

    @Test
    void unregisteredTaskCannotBeRequeuedAndCanBeDeleted() {
        parksAndRequeuesPreparation("run-3");
        var actions = new ExecutionActions(client, Clock.fixed(NOW, ZoneOffset.UTC),
                failed, SERIALIZER, List.of());

        assertThat(actions.runNow("payment", "run-3").success()).isFalse();
        assertThat(failed.find("payment", "run-3")).isPresent();
        assertThat(actions.delete("payment", "run-3").success()).isTrue();
        assertThat(failed.find("payment", "run-3")).isEmpty();
    }

    private void parksAndRequeuesPreparation(String id) {
        Payload payload = new Payload("txn-3");
        var instance = TASK.instance(id, payload);
        client.scheduleIfNotExists(instance, NOW);
        jdbc.update("update scheduled_tasks set picked = true, picked_by = 'node-a',"
                + " version = version + 1 where task_name = ? and task_instance = ?",
                TASK.getName(), id);
        var execution = new Execution(NOW, instance, true, "node-a",
                null, null, 3, NOW, 2);
        failed.park(ExecutionComplete.failure(execution, NOW, NOW.plusSeconds(1),
                new RuntimeException("boom")), Payload.class.getName());
    }

    record Payload(String transactionId) implements Serializable { }
}
