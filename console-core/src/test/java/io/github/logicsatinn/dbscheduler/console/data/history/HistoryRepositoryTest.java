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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

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
        assertThat(repo.schema()).isEqualTo(HistoryRepository.Schema.CURRENT);
        var emptyDs = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
        assertThat(new HistoryRepository(emptyDs, Dialect.H2).tableExists()).isFalse();
        assertThat(new HistoryRepository(emptyDs, Dialect.H2).schema())
                .isEqualTo(HistoryRepository.Schema.MISSING);
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
        assertThat(repo.createTableScript())
                .contains("create table dsc_execution_history")
                .contains("create table dsc_failed_execution")
                .contains("task_data_type");
    }

    @Test
    void payloadRoundTripsAndRetentionUsesFinishedTime() {
        byte[] payload = new byte[] {0, 1, 2, 3};
        repo.insert(new HistoryEntry(0, "email", "payload", HistoryEntry.Outcome.SUCCEEDED,
                NOW.minus(30, ChronoUnit.DAYS), NOW, 1000,
                null, null, null, "node", payload, "example.PayloadV1"));
        repo.insert(new HistoryEntry(0, "email", "old", HistoryEntry.Outcome.SUCCEEDED,
                NOW.minus(30, ChronoUnit.DAYS), NOW.minus(29, ChronoUnit.DAYS), 1000,
                null, null, null, "node", null, null));

        HistoryEntry saved = repo.forInstance("email", "payload", 1).get(0);
        assertThat(saved.taskData()).containsExactly(payload);
        assertThat(saved.taskDataType()).isEqualTo("example.PayloadV1");
        assertThat(repo.purgeOlderThan(NOW.minus(14, ChronoUnit.DAYS))).isEqualTo(1);
        assertThat(repo.forInstance("email", "payload", 1)).hasSize(1);
    }

    @Test
    void legacySchemaWritesMetadataAndCanBeUpgraded() {
        var legacyDs = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
        new org.springframework.jdbc.core.JdbcTemplate(legacyDs).execute("""
                create table dsc_execution_history (
                  id bigint generated always as identity primary key,
                  task_name varchar(250) not null,
                  task_instance varchar(250) not null,
                  outcome varchar(16) not null,
                  started_at timestamp with time zone not null,
                  finished_at timestamp with time zone not null,
                  duration_ms bigint not null,
                  exception_class varchar(512),
                  exception_message varchar(2000),
                  stacktrace clob,
                  picked_by varchar(255)
                )""");
        var legacy = new HistoryRepository(legacyDs, Dialect.H2);
        assertThat(legacy.schema()).isEqualTo(HistoryRepository.Schema.LEGACY);
        legacy.insert(new HistoryEntry(0, "email", "legacy", HistoryEntry.Outcome.SUCCEEDED,
                NOW, NOW.plusSeconds(1), 1000, null, null, null, "node",
                new byte[] {1, 2}, "PayloadV1"));
        assertThat(legacy.forInstance("email", "legacy", 1).get(0).taskData()).isNull();
        assertThat(legacy.upgradeTableScript()).contains("alter table dsc_execution_history");

        new ResourceDatabasePopulator(new ByteArrayResource(
                legacy.upgradeTableScript().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .execute(legacyDs);
        legacy.refreshSchema();
        assertThat(legacy.schema()).isEqualTo(HistoryRepository.Schema.CURRENT);
    }
}
