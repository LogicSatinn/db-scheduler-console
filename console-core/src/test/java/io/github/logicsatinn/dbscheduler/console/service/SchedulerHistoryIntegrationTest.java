package io.github.logicsatinn.dbscheduler.console.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryFilter;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class SchedulerHistoryIntegrationTest {

    Scheduler scheduler;

    @AfterEach
    void stop() {
        if (scheduler != null) scheduler.stop();
    }

    @Test
    void realExecutionsAreRecordedAndFailuresNeverBreakTasks() {
        DataSource ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        HistoryRepository history = new HistoryRepository(ds, Dialect.H2);

        OneTimeTask<Void> ok = Tasks.oneTime("itest-ok").execute((inst, ctx) -> {});
        OneTimeTask<Void> boom = Tasks.oneTime("itest-boom").execute((inst, ctx) -> {
            throw new RuntimeException("intentional");
        });

        scheduler = Scheduler.create(ds, ok, boom)
                .pollingInterval(Duration.ofMillis(100))
                .addSchedulerListener(new ConsoleSchedulerListener(history))
                .build();
        scheduler.start();

        scheduler.scheduleIfNotExists(ok.instance("1"), Instant.now());
        scheduler.scheduleIfNotExists(boom.instance("2"), Instant.now());

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var page = history.page(new HistoryFilter(null, null, null, null, null, 0, 10));
            assertThat(page.total()).isEqualTo(2);
        });

        var failures = history.recentFailures(5);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).taskName()).isEqualTo("itest-boom");
        assertThat(failures.get(0).exceptionMessage()).isEqualTo("intentional");
    }
}
