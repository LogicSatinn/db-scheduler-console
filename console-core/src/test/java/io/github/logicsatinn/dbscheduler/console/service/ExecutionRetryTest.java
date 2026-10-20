package io.github.logicsatinn.dbscheduler.console.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.FailedExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.io.ObjectInputFilter;
import java.io.Serializable;
import java.sql.Timestamp;
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
import org.springframework.transaction.support.TransactionOperations;

class ExecutionRetryTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");
    static final Serializer SERIALIZER = new FilteringJavaSerializer(
            ObjectInputFilter.Config.createFilter(Payload.class.getName() + ";!*"));
    static final OneTimeTask<Payload> TASK = Tasks.oneTime("payment", Payload.class)
            .execute((instance, context) -> { });
    static final RecurringTask<Void> RECURRING = Tasks.recurring("cleanup", FixedDelay.ofHours(1))
            .execute((instance, context) -> { });

    JdbcTemplate jdbc;
    SchedulerClient client;
    ExecutionRepository live;
    FailedExecutionRepository failed;
    HistoryRepository history;
    ExecutionActions actions;

    @BeforeEach
    void setUp() {
        DataSource ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        jdbc = new JdbcTemplate(ds);
        client = SchedulerClient.Builder.create(ds, TASK, RECURRING).build();
        live = new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2);
        failed = new FailedExecutionRepository(ds, "scheduled_tasks", Dialect.H2);
        history = new HistoryRepository(ds, Dialect.H2);
        actions = actions(SERIALIZER);
    }

    ExecutionActions actions(Serializer serializer) {
        return new ExecutionActions(client, Clock.fixed(NOW, ZoneOffset.UTC), failed, history,
                serializer, List.<Task<?>>of(TASK, RECURRING), TransactionOperations.withoutTransaction());
    }

    void schedule(String id, int failures, boolean picked) {
        client.scheduleIfNotExists(TASK.instance(id, new Payload(id)), NOW.plus(1, ChronoUnit.HOURS));
        jdbc.update("update scheduled_tasks set consecutive_failures = ?, picked = ?"
                + " where task_instance = ?", failures, picked, id);
    }

    void park(String id) {
        jdbc.update("insert into dsc_failed_execution"
                        + " (task_name, task_instance, task_data, task_data_type, priority, failure_count, failed_at)"
                        + " values (?, ?, ?, ?, 0, 4, ?)",
                TASK.getName(), id, Serializer.DEFAULT_JAVA_SERIALIZER.serialize(new Payload(id)),
                Payload.class.getName(), Timestamp.from(NOW));
    }

    long failure(String task, String id, byte[] data, String type) {
        history.insert(new HistoryEntry(0, task, id, HistoryEntry.Outcome.FAILED,
                NOW.minusSeconds(30), NOW.minusSeconds(29), 1000,
                "java.lang.IllegalStateException", "gateway down", "stack", "node-1", data, type));
        return history.forInstance(task, id, 1).get(0).id();
    }

    long recordedFailure(String id) {
        return failure(TASK.getName(), id,
                Serializer.DEFAULT_JAVA_SERIALIZER.serialize(new Payload(id)), Payload.class.getName());
    }

    Instant executionTime(String id) {
        return live.find(TASK.getName(), id).orElseThrow().executionTime();
    }

    @Test
    void retryRunsARetryingExecutionNow() {
        schedule("a", 2, false);

        var result = actions.retry(TASK.getName(), "a");

        assertThat(result.success()).isTrue();
        assertThat(result.message()).isEqualTo("Retry succeeded for a");
        assertThat(executionTime("a")).isEqualTo(NOW);
    }

    @Test
    void retryRequeuesAParkedExecution() {
        park("p");

        var result = actions.retry(TASK.getName(), "p");

        assertThat(result.success()).isTrue();
        assertThat(failed.find(TASK.getName(), "p")).isEmpty();
        assertThat(executionTime("p")).isEqualTo(NOW);
    }

    @Test
    void retryRefusesExecutionsThatHaveNotFailedAreRunningOrAreGone() {
        schedule("fresh", 0, false);
        schedule("running", 3, true);

        var fresh = actions.retry(TASK.getName(), "fresh");
        var running = actions.retry(TASK.getName(), "running");
        var missing = actions.retry(TASK.getName(), "missing");

        assertThat(fresh.success()).isFalse();
        assertThat(fresh.message()).isEqualTo("Retry skipped for fresh — it has not failed");
        assertThat(running.success()).isFalse();
        assertThat(running.message()).contains("executing right now");
        assertThat(missing.success()).isFalse();
        assertThat(missing.message()).isEqualTo("Execution no longer exists");
        assertThat(executionTime("fresh")).isEqualTo(NOW.plus(1, ChronoUnit.HOURS));
        assertThat(executionTime("running")).isEqualTo(NOW.plus(1, ChronoUnit.HOURS));
    }

    @Test
    void bulkRetryCountsExecutionsThatHaveNotFailedAsSkipped() {
        schedule("a", 1, false);
        schedule("b", 0, false);
        park("p");

        var mixed = actions.bulk(ExecutionActions.Kind.RETRY, List.of(
                new ExecutionActions.InstanceRef(TASK.getName(), "a"),
                new ExecutionActions.InstanceRef(TASK.getName(), "b"),
                new ExecutionActions.InstanceRef(TASK.getName(), "p"),
                new ExecutionActions.InstanceRef(TASK.getName(), "missing")));

        assertThat(mixed.success()).isFalse();
        assertThat(mixed.message()).isEqualTo("Retry: 2 succeeded, 1 failed, 1 skipped (not failed)");
        assertThat(executionTime("a")).isEqualTo(NOW);
        assertThat(executionTime("b")).isEqualTo(NOW.plus(1, ChronoUnit.HOURS));

        var skippedOnly = actions.bulk(ExecutionActions.Kind.RETRY, List.of(
                new ExecutionActions.InstanceRef(TASK.getName(), "b")));
        assertThat(skippedOnly.success()).isTrue();
        assertThat(skippedOnly.message()).isEqualTo("Retry: 0 succeeded, 0 failed, 1 skipped (not failed)");
    }

    @Test
    void rerunSchedulesTheRecordedPayloadUnderTheSameInstance() {
        long id = recordedFailure("order-7");

        var result = actions.rerun(id);

        assertThat(result.success()).isTrue();
        assertThat(result.message()).isEqualTo("Re-run scheduled for order-7");
        var row = live.find(TASK.getName(), "order-7").orElseThrow();
        assertThat(row.executionTime()).isEqualTo(NOW);
        assertThat(SERIALIZER.deserialize(Payload.class, row.taskData())).isEqualTo(new Payload("order-7"));

        var again = actions.rerun(id);
        assertThat(again.success()).isFalse();
        assertThat(again.message()).contains("already scheduled");
    }

    @Test
    void rerunRefusesRunsThatCannotBeSafelyRepeated() {
        history.insert(new HistoryEntry(0, TASK.getName(), "ok", HistoryEntry.Outcome.SUCCEEDED,
                NOW.minusSeconds(30), NOW.minusSeconds(29), 1000, null, null, null, "node-1",
                Serializer.DEFAULT_JAVA_SERIALIZER.serialize(new Payload("ok")), Payload.class.getName()));
        long succeeded = history.forInstance(TASK.getName(), "ok", 1).get(0).id();
        long unrecorded = failure(TASK.getName(), "unrecorded", null, null);
        long superseded = recordedFailure("superseded");
        history.insert(new HistoryEntry(0, TASK.getName(), "superseded", HistoryEntry.Outcome.SUCCEEDED,
                NOW.minusSeconds(10), NOW.minusSeconds(9), 1000, null, null, null, "node-1"));
        long recurring = failure(RECURRING.getName(), RecurringTask.INSTANCE, null, "java.lang.Void");
        long unregistered = failure("unregistered", "x", null, "java.lang.Void");
        park("parked");
        long parked = recordedFailure("parked");
        schedule("scheduled", 1, false);
        long scheduled = recordedFailure("scheduled");

        assertThat(actions.rerun(succeeded).message()).isEqualTo("Re-run is only available for failed runs");
        assertThat(actions.rerun(unrecorded).message()).isEqualTo("Re-run failed for unrecorded:"
                + " its payload was not recorded (enable db-scheduler-console.history.store-task-data)");
        assertThat(actions.rerun(superseded).message())
                .isEqualTo("Re-run failed for superseded — it has run again since this failure");
        assertThat(actions.rerun(recurring).message()).isEqualTo("Re-run failed for recurring:"
                + " no registered one-time task named cleanup");
        assertThat(actions.rerun(unregistered).message()).isEqualTo("Re-run failed for x:"
                + " no registered one-time task named unregistered");
        assertThat(actions.rerun(parked).message())
                .isEqualTo("Re-run failed for parked — it is parked; use Retry instead");
        assertThat(actions.rerun(scheduled).message())
                .isEqualTo("Re-run failed for scheduled — it is already scheduled; use Retry instead");
        assertThat(actions.rerun(999_999).message()).isEqualTo("History entry no longer exists");

        assertThat(live.find(TASK.getName(), "ok")).isEmpty();
        assertThat(live.find(TASK.getName(), "unrecorded")).isEmpty();
        assertThat(live.find(TASK.getName(), "superseded")).isEmpty();
        assertThat(live.find("unregistered", "x")).isEmpty();
        assertThat(live.find(TASK.getName(), "parked")).isEmpty();
    }

    @Test
    void rerunAppliesTheRequeueDeserializationSafeguards() {
        org.junit.jupiter.api.Assumptions.assumeTrue(ObjectInputFilter.Config.getSerialFilter() == null);
        long needsFilter = recordedFailure("needs-filter");
        long oversized = failure(TASK.getName(), "oversized",
                new byte[FilteringJavaSerializer.MAX_INPUT_BYTES + 1], Payload.class.getName());

        var unfiltered = actions(Serializer.DEFAULT_JAVA_SERIALIZER).rerun(needsFilter);
        var tooLarge = actions.rerun(oversized);

        assertThat(unfiltered.success()).isFalse();
        assertThat(unfiltered.message()).isEqualTo("Re-run requires a host-configured Java deserialization filter");
        assertThat(tooLarge.success()).isFalse();
        assertThat(tooLarge.message()).isEqualTo("Re-run rejected: payload exceeds input limit");
        assertThat(live.find(TASK.getName(), "needs-filter")).isEmpty();
        assertThat(live.find(TASK.getName(), "oversized")).isEmpty();
    }

    @Test
    void rerunOfAVoidTaskNeedsNoPayloadBytes() {
        OneTimeTask<Void> plain = Tasks.oneTime("plain").execute((instance, context) -> { });
        var plainClient = SchedulerClient.Builder.create(jdbc.getDataSource(), plain).build();
        var plainActions = new ExecutionActions(plainClient, Clock.fixed(NOW, ZoneOffset.UTC), failed,
                history, Serializer.DEFAULT_JAVA_SERIALIZER, List.<Task<?>>of(plain),
                TransactionOperations.withoutTransaction());
        long id = failure("plain", "p-1", null, "java.lang.Void");

        assertThat(plainActions.rerun(id).success()).isTrue();
        assertThat(live.find("plain", "p-1").orElseThrow().executionTime()).isEqualTo(NOW);
    }

    @Test
    void rerunWithoutHistoryIsUnavailable() {
        var result = new ExecutionActions(client, Clock.fixed(NOW, ZoneOffset.UTC)).rerun(1);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("Re-run needs the execution history table");
    }

    record Payload(String id) implements Serializable { }
}
