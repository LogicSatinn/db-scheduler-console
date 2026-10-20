package io.github.logicsatinn.dbscheduler.console.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public class ExecutionRepository {

    public static final String DEFAULT_TABLE_NAME = "scheduled_tasks";

    private static final Pattern TABLE_NAME =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$");

    private static final String COLUMNS =
            "task_name, task_instance, task_data, execution_time, picked, picked_by, "
            + "last_success, last_failure, consecutive_failures, last_heartbeat, version, priority";

    private final JdbcTemplate jdbc;
    private final String table;
    private final Dialect dialect;

    public ExecutionRepository(DataSource dataSource, String tableName, Dialect dialect) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.table = validateTableName(tableName);
        this.dialect = dialect;
    }

    static String validateTableName(String tableName) {
        if (tableName == null || !TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalArgumentException(
                    "db-scheduler.table-name must be a plain SQL identifier, optionally"
                    + " schema-qualified (e.g. scheduled_tasks or myschema.scheduled_tasks),"
                    + " but was: " + tableName);
        }
        return tableName;
    }

    public record LiveCounts(long scheduled, long due, long running, long retrying) {}

    public String tableName() {
        return table;
    }

    public Page<ExecutionRow> page(ExecutionFilter f, Instant now) {
        return page(f, now, false);
    }

    public Page<ExecutionRow> page(ExecutionFilter f, Instant now, boolean includeFailed) {
        return page(f, now, includeFailed, null);
    }

    public Page<ExecutionRow> page(ExecutionFilter f, Instant now, boolean includeFailed,
            Collection<String> kindTaskNames) {
        int size = Keyset.size(f.pageSize());
        String scope = Keyset.scope("executions", f.state(), f.taskName(), f.instanceContains(),
                f.from(), f.to(), size, f.sort(), f.descending(), f.kind());
        Keyset.Cursor cursor = Keyset.decode(f.cursor(), scope, 4);
        String primary = f.sort().column();
        Class<?> type = switch (f.sort()) {
            case EXECUTION_TIME, LAST_HEARTBEAT -> Instant.class;
            case CONSECUTIVE_FAILURES -> Integer.class;
            case TASK_NAME -> String.class;
        };
        List<Keyset.Key> keys = List.of(
                new Keyset.Key(primary, cursor == null ? null : Keyset.value(cursor.values().get(0), type), f.descending()),
                new Keyset.Key("task_name", cursor == null ? null : Keyset.value(cursor.values().get(1), String.class), false),
                new Keyset.Key("task_instance", cursor == null ? null : Keyset.value(cursor.values().get(2), String.class), false),
                new Keyset.Key("parked", cursor == null ? null : Keyset.value(cursor.values().get(3), Integer.class), false));
        if (cursor != null && (keys.get(1).value() == null || keys.get(2).value() == null
                || keys.get(3).value() == null || !List.of(0, 1).contains(keys.get(3).value())
                || f.sort() != SortColumn.LAST_HEARTBEAT && keys.get(0).value() == null))
            throw new IllegalArgumentException("Invalid execution cursor");
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        applyFilter(f, now, where, params);
        if (f.kind() != null) appendTaskNamesIn(kindTaskNames, where, params);
        boolean backwards = cursor != null && cursor.backwards();
        if (cursor != null) Keyset.seek(where, params, keys, backwards, dialect);
        String liveColumns = "task_name, task_instance, execution_time, picked, picked_by, "
                + "last_success, last_failure, COALESCE(consecutive_failures, 0) AS consecutive_failures, last_heartbeat, version, priority, 0 AS parked";
        String source = "SELECT " + liveColumns + " FROM " + table;
        if (includeFailed) {
            source += " UNION ALL SELECT task_name, task_instance, failed_at AS execution_time, "
                    + ((dialect == Dialect.POSTGRES || dialect == Dialect.H2) ? "FALSE" : "0") + " AS picked, picked_by, "
                    + "NULL AS last_success, failed_at AS last_failure, failure_count AS consecutive_failures, "
                    + "NULL AS last_heartbeat, 0 AS version, priority, 1 AS parked FROM " + FailedExecutionRepository.TABLE;
        }
        String sql = "SELECT * FROM (" + source + ") dsc" + where
                + Keyset.order(keys, backwards) + dialect.paginationClause();
        params.addAll(Arrays.asList(dialect.paginationParams(0, size + 1)));
        List<ExecutionRow> rows = jdbc.query(sql, (rs, n) -> new ExecutionRow(
                rs.getString("task_name"), rs.getString("task_instance"), null,
                instant(rs, "execution_time"), rs.getBoolean("picked"), rs.getString("picked_by"),
                instant(rs, "last_success"), instant(rs, "last_failure"), rs.getInt("consecutive_failures"),
                instant(rs, "last_heartbeat"), rs.getLong("version"), rs.getInt("priority"),
                rs.getInt("parked") == 1, null, null, null, null), params.toArray());
        return Keyset.page(rows, size, cursor, scope, row -> List.of(
                new Keyset.Key(primary, switch (f.sort()) {
                    case EXECUTION_TIME -> row.executionTime();
                    case LAST_HEARTBEAT -> row.lastHeartbeat();
                    case TASK_NAME -> row.taskName();
                    case CONSECUTIVE_FAILURES -> row.consecutiveFailures();
                }, f.descending()),
                new Keyset.Key("task_name", row.taskName(), false),
                new Keyset.Key("task_instance", row.instanceId(), false),
                new Keyset.Key("parked", row.parked() ? 1 : 0, false)));
    }

    public byte[] payload(String task, String id, int maxBytes) {
        return jdbc.query("SELECT task_data FROM " + table + " WHERE task_name = ? AND task_instance = ?", rs -> {
            if (!rs.next()) return null;
            try (var in = rs.getBinaryStream(1)) {
                return in == null ? null : in.readNBytes(maxBytes + 1);
            } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        }, task, id);
    }

    public Optional<ExecutionRow> metadata(String task, String id) {
        return jdbc.query("SELECT " + COLUMNS.replace("task_data,", "NULL AS task_data,") + " FROM " + table
                + " WHERE task_name = ? AND task_instance = ?", this::mapRow, task, id).stream().findFirst();
    }

    public Map<InstanceKey, ExecutionRow> metadata(Collection<InstanceKey> keys) {
        if (keys.isEmpty()) return Map.of();
        List<Object> params = new ArrayList<>();
        String sql = "SELECT " + COLUMNS.replace("task_data,", "NULL AS task_data,") + " FROM " + table
                + " WHERE " + InstanceKey.matching(keys, params);
        Map<InstanceKey, ExecutionRow> rows = new HashMap<>();
        for (ExecutionRow row : jdbc.query(sql, this::mapRow, params.toArray())) {
            rows.put(new InstanceKey(row.taskName(), row.instanceId()), row);
        }
        return rows;
    }

    public Optional<ExecutionRow> find(String taskName, String instanceId) {
        List<ExecutionRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM " + table
                        + " WHERE task_name = ? AND task_instance = ?",
                this::mapRow, taskName, instanceId);
        return rows.stream().findFirst();
    }

    public java.util.Optional<Instant> nextExecutionTime(String taskName) {
        List<Instant> result = jdbc.query(
                "SELECT MIN(execution_time) AS next_time FROM " + table
                        + " WHERE task_name = ? AND picked = ?",
                (rs, i) -> instant(rs, "next_time"), taskName, false);
        return result.stream().filter(java.util.Objects::nonNull).findFirst();
    }

    public Map<String, Instant> nextExecutionTimes() {
        Map<String, Instant> byTask = new HashMap<>();
        jdbc.query("SELECT task_name, MIN(execution_time) AS next_time FROM " + table
                        + " WHERE picked = ? GROUP BY task_name",
                rs -> {
                    Instant next = instant(rs, "next_time");
                    if (next != null) {
                        byTask.put(rs.getString("task_name"), next);
                    }
                }, false);
        return byTask;
    }

    public List<String> distinctTaskNames() {
        return jdbc.queryForList(
                "SELECT DISTINCT task_name FROM " + table + " ORDER BY task_name", String.class);
    }

    public LiveCounts counts(Instant now) {
        return new LiveCounts(
                countState(ExecutionState.SCHEDULED, now),
                countState(ExecutionState.DUE, now),
                countState(ExecutionState.RUNNING, now),
                countState(ExecutionState.RETRYING, now));
    }

    private long countState(ExecutionState state, Instant now) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        appendStateCondition(state, now, where, params);
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + where, Long.class, params.toArray());
        return n == null ? 0 : n;
    }

    private void applyFilter(ExecutionFilter f, Instant now, StringBuilder where, List<Object> params) {
        if (f.state() != null) {
            appendStateCondition(f.state(), now, where, params);
            if (f.state() != ExecutionState.FAILED) where.append(" AND parked = 0");
        }
        if (f.taskName() != null && !f.taskName().isBlank()) {
            where.append(" AND task_name = ?");
            params.add(f.taskName());
        }
        if (f.instanceContains() != null && !f.instanceContains().isBlank()) {
            where.append(" AND LOWER(task_instance) LIKE ?");
            params.add("%" + f.instanceContains().toLowerCase(Locale.ROOT) + "%");
        }
        if (f.from() != null) {
            where.append(" AND execution_time >= ?");
            params.add(dialect.jdbcTimestamp(f.from()));
        }
        if (f.to() != null) {
            where.append(" AND execution_time <= ?");
            params.add(dialect.jdbcTimestamp(f.to()));
        }
    }

    private static void appendTaskNamesIn(Collection<String> names, StringBuilder where,
            List<Object> params) {
        if (names == null || names.isEmpty()) {
            where.append(" AND 1=0");
            return;
        }
        where.append(" AND task_name IN (")
                .append(String.join(", ", java.util.Collections.nCopies(names.size(), "?")))
                .append(")");
        params.addAll(names);
    }

    private void appendStateCondition(ExecutionState state, Instant now,
            StringBuilder where, List<Object> params) {
        switch (state) {
            case RUNNING -> where.append(" AND picked = ?").append(add(params, true));
            case RETRYING -> {
                where.append(" AND picked = ?").append(add(params, false));
                where.append(" AND COALESCE(consecutive_failures, 0) > 0");
            }
            case DUE -> {
                where.append(" AND picked = ?").append(add(params, false));
                where.append(" AND COALESCE(consecutive_failures, 0) = 0");
                where.append(" AND execution_time <= ?")
                        .append(add(params, dialect.jdbcTimestamp(now)));
            }
            case SCHEDULED -> {
                where.append(" AND picked = ?").append(add(params, false));
                where.append(" AND COALESCE(consecutive_failures, 0) = 0");
                where.append(" AND execution_time > ?")
                        .append(add(params, dialect.jdbcTimestamp(now)));
            }
            case FAILED -> where.append(" AND parked = 1");
        }
    }

    private static String add(List<Object> params, Object value) {
        params.add(value);
        return "";
    }

    private ExecutionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ExecutionRow(
                rs.getString("task_name"),
                rs.getString("task_instance"),
                rs.getBytes("task_data"),
                instant(rs, "execution_time"),
                rs.getBoolean("picked"),
                rs.getString("picked_by"),
                instant(rs, "last_success"),
                instant(rs, "last_failure"),
                rs.getInt("consecutive_failures"),
                instant(rs, "last_heartbeat"),
                rs.getLong("version"),
                rs.getInt("priority"),
                false,
                null,
                null,
                null,
                null);
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
