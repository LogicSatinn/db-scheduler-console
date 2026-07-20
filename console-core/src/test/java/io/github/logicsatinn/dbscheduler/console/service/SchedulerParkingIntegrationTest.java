package io.github.logicsatinn.dbscheduler.console.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.task.FailureHandler;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.FailedExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryFilter;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class SchedulerParkingIntegrationTest {

    Scheduler scheduler;

    @AfterEach
    void stop() {
        if (scheduler != null) {
            scheduler.stop();
        }
    }

    @Test
    void retriesParksRequeuesSamePayloadAndThenSucceeds() {
        DataSource dataSource = database(true);
        var failed = new FailedExecutionRepository(dataSource, "scheduled_tasks", Dialect.H2);
        var parking = new FailedExecutionParking(failed);
        var shouldSucceed = new AtomicBoolean();
        var received = new AtomicReference<Payload>();
        FailureHandler<Payload> retryPolicy = FailureHandler.<Payload>maxRetries(3)
                .withBackoff(Duration.ofMillis(25), 2)
                .then(parking.failureHandler());
        OneTimeTask<Payload> task = Tasks.oneTime("durable-payment", Payload.class)
                .onFailure(retryPolicy)
                .execute((instance, context) -> {
                    received.set(instance.getData());
                    if (!shouldSucceed.get()) {
                        throw new IllegalStateException("temporary outage");
                    }
                });
        HistoryRepository history = new HistoryRepository(dataSource, Dialect.H2);

        scheduler = Scheduler.create(dataSource, task)
                .pollingInterval(Duration.ofMillis(20))
                .enablePriority()
                .addSchedulerListener(new ConsoleSchedulerListener(history,
                        Serializer.DEFAULT_JAVA_SERIALIZER, List.of(task), true,
                        HistoryFailureReporter.NOOP))
                .build();
        scheduler.start();
        Payload payload = new Payload("payment-42");
        scheduler.scheduleIfNotExists(
                task.instanceBuilder("attempt-1").data(payload).priority(8).build(), Instant.now());

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(failed.count()).isEqualTo(1);
            assertThat(history.page(HistoryFilter.none(0, 20)).total()).isEqualTo(4);
        });
        assertThat(received.get()).isEqualTo(payload);
        assertThat(failed.find(task.getName(), "attempt-1").orElseThrow().priority()).isEqualTo(8);
        assertThat(history.purgeOlderThan(Instant.now().plusSeconds(1))).isEqualTo(4);
        assertThat(failed.count()).isEqualTo(1);

        shouldSucceed.set(true);
        var actions = new ExecutionActions(scheduler, Clock.systemUTC(), failed,
                Serializer.DEFAULT_JAVA_SERIALIZER, List.of(task));
        assertThat(actions.runNow(task.getName(), "attempt-1").success()).isTrue();
        scheduler.triggerCheckForDueExecutions();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(failed.count()).isZero();
            assertThat(history.page(HistoryFilter.none(0, 20)).total()).isEqualTo(1);
            assertThat(new ExecutionRepository(dataSource, "scheduled_tasks", Dialect.H2)
                    .find(task.getName(), "attempt-1")).isEmpty();
        });
        assertThat(history.page(HistoryFilter.none(0, 20)).items())
                .anySatisfy(entry -> {
                    assertThat(entry.outcome().name()).isEqualTo("SUCCEEDED");
                    assertThat(Serializer.DEFAULT_JAVA_SERIALIZER.deserialize(
                            Payload.class, entry.taskData())).isEqualTo(payload);
                });
    }

    @Test
    void historyOutageDoesNotChangeSuccessfulExecutionOutcome() {
        DataSource dataSource = database(false);
        OneTimeTask<Void> task = Tasks.oneTime("history-outage").execute((instance, context) -> { });
        scheduler = Scheduler.create(dataSource, task)
                .pollingInterval(Duration.ofMillis(20))
                .addSchedulerListener(new ConsoleSchedulerListener(
                        new HistoryRepository(dataSource, Dialect.H2)))
                .build();
        scheduler.start();
        scheduler.scheduleIfNotExists(task.instance("success"), Instant.now());

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(new ExecutionRepository(dataSource, "scheduled_tasks", Dialect.H2)
                        .find(task.getName(), "success")).isEmpty());
    }

    @Test
    void severalMissedRecurringTimesProduceOneNativeCatchUpExecution() throws Exception {
        DataSource dataSource = database(true);
        AtomicInteger runs = new AtomicInteger();
        RecurringTask<Void> task = Tasks.recurring(
                        "native-catch-up", FixedDelay.ofSeconds(2))
                .execute((instance, context) -> runs.incrementAndGet());
        scheduler = Scheduler.create(dataSource, task)
                .pollingInterval(Duration.ofMillis(20))
                .build();
        scheduler.scheduleIfNotExists(
                task.instance(RecurringTask.INSTANCE), Instant.now().minusSeconds(10));
        scheduler.start();

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(runs.get()).isEqualTo(1));
        Thread.sleep(500);

        assertThat(runs.get()).isEqualTo(1);
        assertThat(new ExecutionRepository(dataSource, "scheduled_tasks", Dialect.H2)
                .find(task.getName(), RecurringTask.INSTANCE).orElseThrow().executionTime())
                .isAfter(Instant.now());
    }

    private static DataSource database(boolean withConsoleSchema) {
        var builder = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql");
        if (withConsoleSchema) {
            builder.addScript("db-scheduler-console/migrations/h2.sql");
        }
        return builder.build();
    }

    record Payload(String id) implements Serializable { }
}
