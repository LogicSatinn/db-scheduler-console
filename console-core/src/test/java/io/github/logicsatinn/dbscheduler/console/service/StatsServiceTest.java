package io.github.logicsatinn.dbscheduler.console.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class StatsServiceTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:30:00Z");

    DataSource ds;
    JdbcTemplate jdbc;
    StatsService stats;
    HistoryRepository history;

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
        stats = new StatsService(
                new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2),
                history,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    HistoryEntry at(Instant started, HistoryEntry.Outcome outcome) {
        return new HistoryEntry(0, "t", "i", outcome, started, started.plusSeconds(1), 1000,
                null, null, null, "n");
    }

    @Test
    void tilesCombineLiveAndHistoryCounts() {
        jdbc.update("insert into scheduled_tasks (task_name, task_instance, execution_time, picked, version)"
                + " values ('a','1', ?, false, 1)", Timestamp.from(NOW.plusSeconds(600)));
        jdbc.update("insert into scheduled_tasks (task_name, task_instance, execution_time, picked, picked_by, version)"
                + " values ('a','2', ?, true, 'n1', 1)", Timestamp.from(NOW));
        history.insert(at(NOW.minusSeconds(60), HistoryEntry.Outcome.SUCCEEDED));
        history.insert(at(NOW.minusSeconds(120), HistoryEntry.Outcome.FAILED));
        history.insert(at(NOW.minus(3, ChronoUnit.DAYS), HistoryEntry.Outcome.FAILED)); // outside 24h

        var tiles = stats.tiles();
        assertThat(tiles.scheduled()).isEqualTo(1);
        assertThat(tiles.running()).isEqualTo(1);
        assertThat(tiles.succeeded24h()).isEqualTo(1);
        assertThat(tiles.failed24h()).isEqualTo(1);
    }

    @Test
    void throughputBucketsAreHourAlignedOldestFirst() {
        // NOW is 12:30 → window is [13:00 yesterday, 13:00 today), buckets aligned to hours
        history.insert(at(NOW.minusSeconds(300), HistoryEntry.Outcome.SUCCEEDED)); // 12:25 → last bucket
        history.insert(at(NOW.minusSeconds(300), HistoryEntry.Outcome.FAILED));
        history.insert(at(NOW.minus(5, ChronoUnit.HOURS), HistoryEntry.Outcome.SUCCEEDED));
        history.insert(at(NOW.minus(30, ChronoUnit.HOURS), HistoryEntry.Outcome.SUCCEEDED)); // outside

        var buckets = stats.throughputLast24h();
        assertThat(buckets).hasSize(24);
        assertThat(buckets.get(23).hourStart()).isEqualTo(Instant.parse("2026-07-13T12:00:00Z"));
        assertThat(buckets.get(23).succeeded()).isEqualTo(1);
        assertThat(buckets.get(23).failed()).isEqualTo(1);
        assertThat(buckets.get(18).succeeded()).isEqualTo(1); // 07:00–08:00 bucket
        long totalCounted = buckets.stream().mapToLong(b -> b.succeeded() + b.failed()).sum();
        assertThat(totalCounted).isEqualTo(3);
    }

    @Test
    void degradesWhenHistoryTableMissing() {
        DataSource bare = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .build();
        var s = new StatsService(
                new ExecutionRepository(bare, "scheduled_tasks", Dialect.H2),
                new HistoryRepository(bare, Dialect.H2),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(s.historyAvailable()).isFalse();
        assertThat(s.tiles().succeeded24h()).isZero();
        assertThat(s.throughputLast24h()).isEmpty();
    }
}
