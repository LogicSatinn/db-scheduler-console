package io.github.logicsatinn.dbscheduler.console.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** Counts JDBC statements so an N+1 cannot creep back in as tasks are added. */
    static DataSource counting(DataSource delegate, AtomicInteger statements) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(delegate, method, args);
                    if (!(result instanceof Connection connection)) {
                        return result;
                    }
                    return Proxy.newProxyInstance(
                            Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                            (c, m, a) -> {
                                if (m.getName().startsWith("prepareStatement")
                                        || m.getName().equals("createStatement")) {
                                    statements.incrementAndGet();
                                }
                                return invoke(connection, m, a);
                            });
                });
    }

    static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void rowsQueryCountDoesNotGrowWithTheNumberOfTasks() {
        List<Task<?>> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            many.add(Tasks.recurring("task-" + i, FixedDelay.ofHours(1)).execute((in, c) -> {}));
        }
        var statements = new AtomicInteger();
        DataSource counted = counting(ds, statements);
        var service = new RecurringTasksService(many,
                new ExecutionRepository(counted, "scheduled_tasks", Dialect.H2),
                new HistoryRepository(counted, Dialect.H2));

        assertThat(service.rows()).hasSize(10);

        // tableExists + one batched query per repository — never one query per task.
        assertThat(statements.get()).isLessThanOrEqualTo(3);
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
