package io.github.logicsatinn.dbscheduler.console.data.history;

import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.Page;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

public class HistoryRepository {

    public static final String TABLE = "dsc_execution_history";
    static final int MAX_MESSAGE = 2000;
    static final int MAX_STACKTRACE = 30000;

    private static final String METADATA_COLUMNS =
            "id, task_name, task_instance, outcome, started_at, finished_at, duration_ms, "
            + "exception_class, exception_message, stacktrace, picked_by";

    private final JdbcTemplate jdbc;
    private final Dialect dialect;
    private volatile Schema detectedSchema;

    public HistoryRepository(DataSource dataSource, Dialect dialect) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.dialect = dialect;
    }

    public record OutcomeCounts(long succeeded, long failed) {}

    public record OutcomePoint(Instant startedAt, HistoryEntry.Outcome outcome) {}

    public enum Schema { MISSING, LEGACY, CURRENT }

    /**
     * M1 tables contain metadata only. M2 tables also contain task_data and task_data_type.
     * SQL probing is more reliable across the six supported dialects than metadata casing rules.
     */
    public Schema schema() {
        Schema known = detectedSchema;
        if (known != null) {
            return known;
        }
        try {
            jdbc.query("SELECT task_data, task_data_type FROM " + TABLE + " WHERE 1=0",
                    rs -> { });
            detectedSchema = Schema.CURRENT;
            return detectedSchema;
        } catch (DataAccessException currentProbeFailed) {
            try {
                jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE + " WHERE 1=0", Long.class);
                detectedSchema = Schema.LEGACY;
                return detectedSchema;
            } catch (DataAccessException tableProbeFailed) {
                // Do not cache MISSING: migrations may be applied while the app is running.
                return Schema.MISSING;
            }
        }
    }

    public void refreshSchema() {
        detectedSchema = null;
    }

    public boolean tableExists() {
        return schema() != Schema.MISSING;
    }

    public void insert(HistoryEntry e) {
        Schema schema = schema();
        if (schema == Schema.MISSING) {
            throw new IllegalStateException("History table " + TABLE + " is missing");
        }
        String payloadColumns = schema == Schema.CURRENT ? ", task_data, task_data_type" : "";
        String payloadValues = schema == Schema.CURRENT
                ? (dialect == Dialect.SQLSERVER ? ", CONVERT(varbinary(max), ?), ?" : ", ?, ?")
                : "";
        List<Object> params = new ArrayList<>(List.of(
                e.taskName(), e.instanceId(), e.outcome().name(),
                dialect.jdbcTimestamp(e.startedAt()), dialect.jdbcTimestamp(e.finishedAt()),
                e.durationMs()));
        params.add(truncate(e.exceptionClass(), 512));
        params.add(truncate(e.exceptionMessage(), MAX_MESSAGE));
        params.add(truncate(e.stacktrace(), MAX_STACKTRACE));
        params.add(e.pickedBy());
        if (schema == Schema.CURRENT) {
            params.add(e.taskData());
            params.add(truncate(e.taskDataType(), 512));
        }
        jdbc.update("INSERT INTO " + TABLE
                        + " (task_name, task_instance, outcome, started_at, finished_at, duration_ms,"
                        + " exception_class, exception_message, stacktrace, picked_by"
                        + payloadColumns + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?"
                        + payloadValues + ")",
                params.toArray());
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
            params.add(dialect.jdbcTimestamp(f.from()));
        }
        if (f.to() != null) {
            where.append(" AND started_at <= ?");
            params.add(dialect.jdbcTimestamp(f.to()));
        }

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + where, Long.class, params.toArray());

        String sql = "SELECT " + columns() + " FROM " + TABLE + where
                + " ORDER BY started_at DESC, id DESC" + dialect.paginationClause();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.addAll(Arrays.asList(
                dialect.paginationParams(f.page() * f.pageSize(), f.pageSize())));

        List<HistoryEntry> rows = jdbc.query(sql, this::mapRow, pageParams.toArray());
        return new Page<>(rows, f.page(), f.pageSize(), total == null ? 0 : total);
    }

    public List<HistoryEntry> forInstance(String taskName, String instanceId, int limit) {
        String sql = "SELECT " + columns() + " FROM " + TABLE
                + " WHERE task_name = ? AND task_instance = ?"
                + " ORDER BY started_at DESC, id DESC" + dialect.paginationClause();
        List<Object> params = new ArrayList<>(List.of(taskName, instanceId));
        params.addAll(Arrays.asList(dialect.paginationParams(0, limit)));
        return jdbc.query(sql, this::mapRow, params.toArray());
    }

    public java.util.Optional<HistoryEntry> latestForTask(String taskName) {
        String sql = "SELECT " + columns() + " FROM " + TABLE + " WHERE task_name = ?"
                + " ORDER BY started_at DESC, id DESC" + dialect.paginationClause();
        List<Object> params = new ArrayList<>(List.of(taskName));
        params.addAll(Arrays.asList(dialect.paginationParams(0, 1)));
        return jdbc.query(sql, this::mapRow, params.toArray()).stream().findFirst();
    }

    /**
     * Newest entry per task, batched — the recurring page needs one per task. Joining on
     * MAX(id) is portable across all six dialects; window functions and LIMIT-in-subquery are
     * not. id is monotonic per insert, so it is also the tiebreak the per-task query uses.
     */
    public Map<String, HistoryEntry> latestPerTask() {
        String sql = "SELECT " + qualifiedColumns() + " FROM " + TABLE + " h"
                + " JOIN (SELECT task_name, MAX(id) AS max_id FROM " + TABLE
                + " GROUP BY task_name) latest ON h.id = latest.max_id";
        Map<String, HistoryEntry> byTask = new HashMap<>();
        for (HistoryEntry e : jdbc.query(sql, this::mapRow)) {
            byTask.put(e.taskName(), e);
        }
        return byTask;
    }

    private String qualifiedColumns() {
        String metadata = Arrays.stream(METADATA_COLUMNS.split(", ")).map(c -> "h." + c)
                .collect(Collectors.joining(", "));
        return schema() == Schema.CURRENT
                ? metadata + ", h.task_data, h.task_data_type"
                : metadata + ", NULL AS task_data, NULL AS task_data_type";
    }

    public int purgeOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM " + TABLE + " WHERE finished_at < ?",
                dialect.jdbcTimestamp(cutoff));
    }

    public OutcomeCounts countsSince(Instant since) {
        long succeeded = countOutcome("SUCCEEDED", since);
        long failed = countOutcome("FAILED", since);
        return new OutcomeCounts(succeeded, failed);
    }

    private long countOutcome(String outcome, Instant since) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE outcome = ? AND started_at >= ?",
                Long.class, outcome, dialect.jdbcTimestamp(since));
        return n == null ? 0 : n;
    }

    public List<HistoryEntry> recentFailures(int limit) {
        String sql = "SELECT " + columns() + " FROM " + TABLE + " WHERE outcome = 'FAILED'"
                + " ORDER BY started_at DESC, id DESC" + dialect.paginationClause();
        return jdbc.query(sql, this::mapRow, dialect.paginationParams(0, limit));
    }

    public List<OutcomePoint> window(Instant from, Instant to, int cap) {
        String sql = "SELECT started_at, outcome FROM " + TABLE
                + " WHERE started_at >= ? AND started_at < ?"
                + " ORDER BY started_at ASC, id ASC" + dialect.paginationClause();
        List<Object> params = new ArrayList<>(List.of(
                dialect.jdbcTimestamp(from), dialect.jdbcTimestamp(to)));
        params.addAll(Arrays.asList(dialect.paginationParams(0, cap)));
        return jdbc.query(sql, (rs, i) -> new OutcomePoint(
                instant(rs, "started_at"),
                HistoryEntry.Outcome.valueOf(rs.getString("outcome"))), params.toArray());
    }

    public String createTableScript() {
        return readScript(dialect.migrationResource());
    }

    public String upgradeTableScript() {
        return readScript(dialect.upgradeMigrationResource());
    }

    private String readScript(String resource) {
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

    private String columns() {
        return schema() == Schema.CURRENT
                ? METADATA_COLUMNS + ", task_data, task_data_type"
                : METADATA_COLUMNS + ", NULL AS task_data, NULL AS task_data_type";
    }

    private HistoryEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new HistoryEntry(
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
                rs.getString("picked_by"),
                rs.getBytes("task_data"),
                rs.getString("task_data_type"));
    }

    private Instant instant(ResultSet rs, String col) throws SQLException {
        if (dialect == Dialect.SQLSERVER) {
            OffsetDateTime value = rs.getObject(col, OffsetDateTime.class);
            return value == null ? null : value.toInstant();
        }
        java.sql.Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
