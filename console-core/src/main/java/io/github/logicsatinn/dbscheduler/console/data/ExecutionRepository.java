package io.github.logicsatinn.dbscheduler.console.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class ExecutionRepository {

    /** db-scheduler's own default when {@code db-scheduler.table-name} is not configured. */
    public static final String DEFAULT_TABLE_NAME = "scheduled_tasks";

    /** A bare identifier, optionally schema-qualified. */
    private static final Pattern TABLE_NAME =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$");

    private static final String COLUMNS =
            "task_name, task_instance, task_data, execution_time, picked, picked_by, "
            + "last_success, last_failure, consecutive_failures, last_heartbeat, version";

    private final JdbcTemplate jdbc;
    private final String table;
    private final Dialect dialect;

    public ExecutionRepository(DataSource dataSource, String tableName, Dialect dialect) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.table = validateTableName(tableName);
        this.dialect = dialect;
    }

    /**
     * The table name is concatenated into every query, so it is the one identifier a
     * deployment can inject into our SQL. Fail fast at startup rather than quote per dialect.
     */
    private static String validateTableName(String tableName) {
        if (tableName == null || !TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalArgumentException(
                    "db-scheduler.table-name must be a plain SQL identifier, optionally"
                    + " schema-qualified (e.g. scheduled_tasks or myschema.scheduled_tasks),"
                    + " but was: " + tableName);
        }
        return tableName;
    }

    public record LiveCounts(long scheduled, long due, long running, long failing) {}

    public String tableName() {
        return table;
    }

    public Page<ExecutionRow> page(ExecutionFilter f, Instant now) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        applyFilter(f, now, where, params);

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + where, Long.class, params.toArray());

        String orderBy = " ORDER BY " + f.sort().column() + (f.descending() ? " DESC" : " ASC")
                + ", task_name, task_instance";
        String sql = "SELECT " + COLUMNS + " FROM " + table + where + orderBy
                + dialect.paginationClause();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.addAll(Arrays.asList(
                dialect.paginationParams(f.page() * f.pageSize(), f.pageSize())));

        List<ExecutionRow> rows = jdbc.query(sql, ROW_MAPPER, pageParams.toArray());
        return new Page<>(rows, f.page(), f.pageSize(), total == null ? 0 : total);
    }

    public Optional<ExecutionRow> find(String taskName, String instanceId) {
        List<ExecutionRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM " + table
                        + " WHERE task_name = ? AND task_instance = ?",
                ROW_MAPPER, taskName, instanceId);
        return rows.stream().findFirst();
    }

    public java.util.Optional<Instant> nextExecutionTime(String taskName) {
        List<Timestamp> result = jdbc.query(
                "SELECT MIN(execution_time) AS next_time FROM " + table
                        + " WHERE task_name = ? AND picked = ?",
                (rs, i) -> rs.getTimestamp("next_time"), taskName, false);
        return result.stream().filter(java.util.Objects::nonNull).findFirst().map(Timestamp::toInstant);
    }

    /** Earliest unpicked execution per task, batched — the recurring page needs one per task. */
    public Map<String, Instant> nextExecutionTimes() {
        Map<String, Instant> byTask = new HashMap<>();
        jdbc.query("SELECT task_name, MIN(execution_time) AS next_time FROM " + table
                        + " WHERE picked = ? GROUP BY task_name",
                rs -> {
                    Timestamp next = rs.getTimestamp("next_time");
                    if (next != null) {
                        byTask.put(rs.getString("task_name"), next.toInstant());
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
                countState(ExecutionState.FAILING, now));
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
            params.add(Timestamp.from(f.from()));
        }
        if (f.to() != null) {
            where.append(" AND execution_time <= ?");
            params.add(Timestamp.from(f.to()));
        }
    }

    private void appendStateCondition(ExecutionState state, Instant now,
            StringBuilder where, List<Object> params) {
        switch (state) {
            case RUNNING -> where.append(" AND picked = ?").append(add(params, true));
            case FAILING -> {
                where.append(" AND picked = ?").append(add(params, false));
                where.append(" AND COALESCE(consecutive_failures, 0) > 0");
            }
            case DUE -> {
                where.append(" AND picked = ?").append(add(params, false));
                where.append(" AND COALESCE(consecutive_failures, 0) = 0");
                where.append(" AND execution_time <= ?").append(add(params, Timestamp.from(now)));
            }
            case SCHEDULED -> {
                where.append(" AND picked = ?").append(add(params, false));
                where.append(" AND COALESCE(consecutive_failures, 0) = 0");
                where.append(" AND execution_time > ?").append(add(params, Timestamp.from(now)));
            }
        }
    }

    /** Adds a param and returns "" so it can be chained inside append(). */
    private static String add(List<Object> params, Object value) {
        params.add(value);
        return "";
    }

    private static final RowMapper<ExecutionRow> ROW_MAPPER = (rs, rowNum) -> new ExecutionRow(
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
            rs.getLong("version"));

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }
}
