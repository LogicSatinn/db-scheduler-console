package io.github.logicsatinn.dbscheduler.console.data.history;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.Page;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class HistoryRepositoryTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    DataSource ds;
    HistoryRepository repo;

    @BeforeEach
    void setUp() {
        ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        repo = new HistoryRepository(ds, Dialect.H2);
    }

    HistoryEntry entry(String task, String id, HistoryEntry.Outcome outcome, Instant started, String excMessage) {
        return new HistoryEntry(0, task, id, outcome, started, started.plusSeconds(2), 2000,
                outcome == HistoryEntry.Outcome.FAILED ? "java.lang.RuntimeException" : null,
                excMessage, excMessage == null ? null : "stack\n", "node-1");
    }

    @Test
    void tableExistsDetection() {
        assertThat(repo.tableExists()).isTrue();
        var emptyDs = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
        assertThat(new HistoryRepository(emptyDs, Dialect.H2).tableExists()).isFalse();
    }

    @Test
    void insertPageAndFilter() {
        repo.insert(entry("email", "1", HistoryEntry.Outcome.SUCCEEDED, NOW.minusSeconds(60), null));
        repo.insert(entry("email", "2", HistoryEntry.Outcome.FAILED, NOW.minusSeconds(50), "SMTP timeout"));
        repo.insert(entry("report", "3", HistoryEntry.Outcome.SUCCEEDED, NOW.minusSeconds(40), null));

        Page<HistoryEntry> all = repo.page(new HistoryFilter(null, null, null, null, null, 0, 10));
        assertThat(all.total()).isEqualTo(3);
        // newest first
        assertThat(all.items().get(0).instanceId()).isEqualTo("3");

        Page<HistoryEntry> failed = repo.page(new HistoryFilter(null, HistoryEntry.Outcome.FAILED, null, null, null, 0, 10));
        assertThat(failed.items()).hasSize(1);
        assertThat(failed.items().get(0).exceptionMessage()).isEqualTo("SMTP timeout");

        Page<HistoryEntry> text = repo.page(new HistoryFilter(null, null, "smtp", null, null, 0, 10));
        assertThat(text.items()).hasSize(1);

        Page<HistoryEntry> byTask = repo.page(new HistoryFilter("report", null, null, null, null, 0, 10));
        assertThat(byTask.items()).hasSize(1);
    }

    @Test
    void truncatesOversizedMessageAndStacktrace() {
        String longMsg = "x".repeat(5000);
        repo.insert(new HistoryEntry(0, "t", "1", HistoryEntry.Outcome.FAILED,
                NOW, NOW.plusSeconds(1), 1000, "E", longMsg, longMsg.repeat(10), "n"));
        HistoryEntry saved = repo.page(new HistoryFilter(null, null, null, null, null, 0, 1)).items().get(0);
        assertThat(saved.exceptionMessage()).hasSize(2000);
        assertThat(saved.stacktrace()).hasSize(30000);
    }

    @Test
    void forInstancePurgeCountsRecentFailuresWindow() {
        repo.insert(entry("email", "1", HistoryEntry.Outcome.FAILED, NOW.minus(30, ChronoUnit.DAYS), "old"));
        repo.insert(entry("email", "1", HistoryEntry.Outcome.SUCCEEDED, NOW.minusSeconds(90), null));
        repo.insert(entry("email", "1", HistoryEntry.Outcome.FAILED, NOW.minusSeconds(30), "recent"));
        repo.insert(entry("other", "9", HistoryEntry.Outcome.SUCCEEDED, NOW.minusSeconds(10), null));

        assertThat(repo.forInstance("email", "1", 10)).hasSize(3);
        assertThat(repo.forInstance("email", "1", 2)).hasSize(2);

        var counts = repo.countsSince(NOW.minus(1, ChronoUnit.DAYS));
        assertThat(counts.succeeded()).isEqualTo(2);
        assertThat(counts.failed()).isEqualTo(1);

        assertThat(repo.recentFailures(5)).extracting(HistoryEntry::exceptionMessage)
                .containsExactly("recent", "old");

        assertThat(repo.window(NOW.minus(1, ChronoUnit.HOURS), NOW, 1000)).hasSize(3);

        int purged = repo.purgeOlderThan(NOW.minus(14, ChronoUnit.DAYS));
        assertThat(purged).isEqualTo(1);
        assertThat(repo.page(new HistoryFilter(null, null, null, null, null, 0, 10)).total()).isEqualTo(3);
    }

    @Test
    void latestPerTaskReturnsTheNewestEntryForEveryTaskInOneQuery() {
        repo.insert(entry("email", "1", HistoryEntry.Outcome.FAILED, NOW.minusSeconds(90), "older"));
        repo.insert(entry("email", "2", HistoryEntry.Outcome.SUCCEEDED, NOW.minusSeconds(30), null));
        repo.insert(entry("report", "3", HistoryEntry.Outcome.FAILED, NOW.minusSeconds(10), "newest"));

        var latest = repo.latestPerTask();

        assertThat(latest).containsOnlyKeys("email", "report");
        assertThat(latest.get("email").instanceId()).isEqualTo("2");
        assertThat(latest.get("email").outcome()).isEqualTo(HistoryEntry.Outcome.SUCCEEDED);
        assertThat(latest.get("report").exceptionMessage()).isEqualTo("newest");
    }

    @Test
    void latestPerTaskTiebreaksOnIdWhenTimestampsMatch() {
        repo.insert(entry("email", "first", HistoryEntry.Outcome.FAILED, NOW, "first"));
        repo.insert(entry("email", "second", HistoryEntry.Outcome.SUCCEEDED, NOW, null));

        assertThat(repo.latestPerTask().get("email").instanceId()).isEqualTo("second");
    }

    @Test
    void latestPerTaskIsEmptyWhenThereIsNoHistory() {
        assertThat(repo.latestPerTask()).isEmpty();
    }

    @Test
    void createTableScriptReturnsDialectDdl() {
        assertThat(repo.createTableScript()).contains("create table dsc_execution_history");
    }
}
