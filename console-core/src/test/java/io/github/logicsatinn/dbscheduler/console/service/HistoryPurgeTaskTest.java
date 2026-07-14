package io.github.logicsatinn.dbscheduler.console.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import io.github.logicsatinn.dbscheduler.console.ConsoleAvailability;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class HistoryPurgeTaskTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final Duration RETENTION = Duration.ofDays(14);

    final Logger taskLog = (Logger) LoggerFactory.getLogger(HistoryPurgeTask.class);
    final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logged.start();
        taskLog.addAppender(logged);
    }

    @AfterEach
    void releaseLogs() {
        taskLog.detachAppender(logged);
    }

    /** db-scheduler's own schema only — the optional history migration has not been applied. */
    static DataSource withoutHistoryTable() {
        return new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .build();
    }

    static DataSource withHistoryTable() {
        return new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
    }

    static void run(RecurringTask<Void> task) {
        task.execute(task.instance(RecurringTask.INSTANCE), null);
    }

    static void insert(HistoryRepository history, String instanceId, Instant startedAt) {
        history.insert(new HistoryEntry(0, "email", instanceId, HistoryEntry.Outcome.SUCCEEDED,
                startedAt, startedAt.plusSeconds(1), 1000, null, null, null, "node-1"));
    }

    @Test
    void warnsAndSkipsWhenHistoryTableIsMissing() {
        var history = new HistoryRepository(withoutHistoryTable(), Dialect.H2);
        var task = HistoryPurgeTask.create(
                history, new ConsoleAvailability(Dialect.H2), RETENTION, CLOCK);

        assertThatCode(() -> run(task)).doesNotThrowAnyException();

        assertThat(logged.list)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage())
                        .contains("dsc_execution_history")
                        .contains("db-scheduler-console/migrations/"));
    }

    @Test
    void skipsWhenDatabaseIsUnsupported() {
        var ds = withHistoryTable();
        var history = new HistoryRepository(ds, Dialect.H2);
        insert(history, "old", NOW.minus(30, ChronoUnit.DAYS));
        var task = HistoryPurgeTask.create(
                history, new ConsoleAvailability(null), RETENTION, CLOCK);

        assertThatCode(() -> run(task)).doesNotThrowAnyException();

        assertThat(history.forInstance("email", "old", 10)).hasSize(1);
    }

    @Test
    void purgesEntriesOlderThanRetention() {
        var history = new HistoryRepository(withHistoryTable(), Dialect.H2);
        insert(history, "old", NOW.minus(30, ChronoUnit.DAYS));
        insert(history, "recent", NOW.minus(1, ChronoUnit.DAYS));
        var task = HistoryPurgeTask.create(
                history, new ConsoleAvailability(Dialect.H2), RETENTION, CLOCK);

        run(task);

        assertThat(history.forInstance("email", "old", 10)).isEmpty();
        assertThat(history.forInstance("email", "recent", 10)).hasSize(1);
    }
}
