package io.github.logicsatinn.dbscheduler.console.data.history;

import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.Page;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class HistoryRepository {

    public static final String TABLE = "dsc_execution_history";
    static final int MAX_MESSAGE = 2000;
    static final int MAX_STACKTRACE = 30000;

    private static final String COLUMNS =
            "id, task_name, task_instance, outcome, started_at, finished_at, duration_ms, "
            + "exception_class, exception_message, stacktrace, picked_by";

    private final JdbcTemplate jdbc;
    private final Dialect dialect;

    public HistoryRepository(DataSource dataSource, Dialect dialect) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.dialect = dialect;
    }

    public record OutcomeCounts(long succeeded, long failed) {}

    public record OutcomePoint(Instant startedAt, HistoryEntry.Outcome outcome) {}

    public boolean tableExists() {
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE + " WHERE 1=0", Long.class);
            return true;
        } catch (DataAccessException e) {
            return false;
        }
    }

    public void insert(HistoryEntry e) {
        jdbc.update("INSERT INTO " + TABLE
                        + " (task_name, task_instance, outcome, started_at, finished_at, duration_ms,"
                        + " exception_class, exception_message, stacktrace, picked_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                e.taskName(), e.instanceId(), e.outcome().name(),
                Timestamp.from(e.startedAt()), Timestamp.from(e.finishedAt()), e.durationMs(),
                truncate(e.exceptionClass(), 512), truncate(e.exceptionMessage(), MAX_MESSAGE),
                truncate(e.stacktrace(), MAX_STACKTRACE), e.pickedBy());
    }

    public Page<HistoryEntry> page(HistoryFilter f) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (f.taskName() != null && !f.taskName().isBlank()) {
            where.append(" AND task_name = ?");
            params.add(f.taskName());
        }
        if (f.outcome() != null) {
            where.append(" AND outcome = ?");
            params.add(f.outcome().name());
        }
        if (f.textSearch() != null && !f.textSearch().isBlank()) {
            where.append(" AND (LOWER(task_instance) LIKE ? OR LOWER(exception_class) LIKE ?"
                    + " OR LOWER(exception_message) LIKE ?)");
            String like = "%" + f.textSearch().toLowerCase(Locale.ROOT) + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (f.from() != null) {
            where.append(" AND started_at >= ?");
            params.add(Timestamp.from(f.from()));
        }
        if (f.to() != null) {
            where.append(" AND started_at <= ?");
            params.add(Timestamp.from(f.to()));
        }

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + where, Long.class, params.toArray());

        String sql = "SELECT " + COLUMNS + " FROM " + TABLE + where
                + " ORDER BY started_at DESC, id DESC" + dialect.paginationClause();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.addAll(Arrays.asList(
                dialect.paginationParams(f.page() * f.pageSize(), f.pageSize())));

        List<HistoryEntry> rows = jdbc.query(sql, ROW_MAPPER, pageParams.toArray());
        return new Page<>(rows, f.page(), f.pageSize(), total == null ? 0 : total);
    }

    public List<HistoryEntry> forInstance(String taskName, String instanceId, int limit) {
        String sql = "SELECT " + COLUMNS + " FROM " + TABLE
                + " WHERE task_name = ? AND task_instance = ?"
                + " ORDER BY started_at DESC, id DESC" + dialect.paginationClause();
        List<Object> params = new ArrayList<>(List.of(taskName, instanceId));
        params.addAll(Arrays.asList(dialect.paginationParams(0, limit)));
        return jdbc.query(sql, ROW_MAPPER, params.toArray());
    }

    public int purgeOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM " + TABLE + " WHERE started_at < ?",
                Timestamp.from(cutoff));
    }

    public OutcomeCounts countsSince(Instant since) {
        long succeeded = countOutcome("SUCCEEDED", since);
        long failed = countOutcome("FAILED", since);
        return new OutcomeCounts(succeeded, failed);
    }

    private long countOutcome(String outcome, Instant since) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE outcome = ? AND started_at >= ?",
                Long.class, outcome, Timestamp.from(since));
        return n == null ? 0 : n;
    }

    public List<HistoryEntry> recentFailures(int limit) {
        String sql = "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE outcome = 'FAILED'"
                + " ORDER BY started_at DESC, id DESC" + dialect.paginationClause();
        return jdbc.query(sql, ROW_MAPPER, dialect.paginationParams(0, limit));
    }

    public List<OutcomePoint> window(Instant from, Instant to, int cap) {
        String sql = "SELECT started_at, outcome FROM " + TABLE
                + " WHERE started_at >= ? AND started_at < ?"
                + " ORDER BY started_at ASC, id ASC" + dialect.paginationClause();
        List<Object> params = new ArrayList<>(List.of(Timestamp.from(from), Timestamp.from(to)));
        params.addAll(Arrays.asList(dialect.paginationParams(0, cap)));
        return jdbc.query(sql, (rs, i) -> new OutcomePoint(
                rs.getTimestamp("started_at").toInstant(),
                HistoryEntry.Outcome.valueOf(rs.getString("outcome"))), params.toArray());
    }

    public String createTableScript() {
        String resource = dialect.migrationResource();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing migration resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static final RowMapper<HistoryEntry> ROW_MAPPER = (rs, rowNum) -> new HistoryEntry(
            rs.getLong("id"),
            rs.getString("task_name"),
            rs.getString("task_instance"),
            HistoryEntry.Outcome.valueOf(rs.getString("outcome")),
            instant(rs, "started_at"),
            instant(rs, "finished_at"),
            rs.getLong("duration_ms"),
            rs.getString("exception_class"),
            rs.getString("exception_message"),
            rs.getString("stacktrace"),
            rs.getString("picked_by"));

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
