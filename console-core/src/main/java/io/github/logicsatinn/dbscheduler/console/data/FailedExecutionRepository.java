package io.github.logicsatinn.dbscheduler.console.data;

import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Persistence for one-time executions parked after their retry policy is exhausted. */
public class FailedExecutionRepository {

    public static final String TABLE = "dsc_failed_execution";

    private static final int MAX_MESSAGE = 2000;
    private static final int MAX_STACKTRACE = 30000;
    private static final String COLUMNS =
            "task_name, task_instance, task_data, task_data_type, priority, failure_count, "
            + "failed_at, picked_by, exception_class, exception_message, stacktrace";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final String scheduledTable;
    private final Dialect dialect;

    public FailedExecutionRepository(DataSource dataSource, String scheduledTable, Dialect dialect) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.scheduledTable = ExecutionRepository.validateTableName(scheduledTable);
        this.dialect = dialect;
    }

    public boolean tableExists() {
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE + " WHERE 1=0", Long.class);
            return true;
        } catch (DataAccessException e) {
            return false;
        }
    }

    public Page<ExecutionRow> page(ExecutionFilter filter) {
        if (!tableExists() || (filter.state() != null && filter.state() != ExecutionState.FAILED)) {
            return new Page<>(List.of(), filter.page(), filter.pageSize(), 0);
        }
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        applyFilter(filter, where, params);

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE + where, Long.class, params.toArray());
        String orderBy = " ORDER BY " + failedSortColumn(filter.sort())
                + (filter.descending() ? " DESC" : " ASC") + ", task_name, task_instance";
        String sql = "SELECT " + COLUMNS + " FROM " + TABLE + where + orderBy
                + dialect.paginationClause();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.addAll(Arrays.asList(dialect.paginationParams(
                filter.page() * filter.pageSize(), filter.pageSize())));
        return new Page<>(jdbc.query(sql, this::mapRow, pageParams.toArray()),
                filter.page(), filter.pageSize(), total == null ? 0 : total);
    }

    public Optional<ExecutionRow> find(String taskName, String instanceId) {
        if (!tableExists()) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE task_name = ? AND task_instance = ?",
                this::mapRow, taskName, instanceId).stream().findFirst();
    }

    public List<String> distinctTaskNames() {
        if (!tableExists()) {
            return List.of();
        }
        return jdbc.queryForList(
                "SELECT DISTINCT task_name FROM " + TABLE + " ORDER BY task_name", String.class);
    }

    public long count() {
        if (!tableExists()) {
            return 0;
        }
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE, Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Atomically copies the exact serialized live payload into the parking table and removes
     * the picked row. Any failure rolls the insert back and leaves the live row authoritative.
     */
    public void park(ExecutionComplete complete, String taskDataType) {
        transaction.executeWithoutResult(status -> {
            Execution execution = complete.getExecution();
            Throwable cause = complete.getCause().orElse(null);
            int inserted = jdbc.update("INSERT INTO " + TABLE
                            + " (task_name, task_instance, task_data, task_data_type, priority,"
                            + " failure_count, failed_at, picked_by, exception_class,"
                            + " exception_message, stacktrace)"
                            + " SELECT task_name, task_instance, task_data, ?, COALESCE(priority, 0),"
                            + " ?, ?, picked_by, ?, ?, ? FROM " + scheduledTable
                            + " WHERE task_name = ? AND task_instance = ? AND version = ? AND picked = ?",
                    truncate(taskDataType, 512), execution.consecutiveFailures + 1,
                    dialect.jdbcTimestamp(complete.getTimeDone()),
                    cause == null ? null : truncate(cause.getClass().getName(), 512),
                    cause == null ? null : truncate(cause.getMessage(), MAX_MESSAGE),
                    cause == null ? null : truncate(Stacktraces.render(cause), MAX_STACKTRACE),
                    execution.getTaskName(), execution.getId(), execution.version, true);
            if (inserted != 1) {
                throw new ConcurrencyFailureException(
                        "Live execution changed before it could be parked: "
                                + execution.getTaskName() + "/" + execution.getId());
            }
            int deleted = jdbc.update("DELETE FROM " + scheduledTable
                            + " WHERE task_name = ? AND task_instance = ? AND version = ? AND picked = ?",
                    execution.getTaskName(), execution.getId(), execution.version, true);
            if (deleted != 1) {
                throw new ConcurrencyFailureException(
                        "Live execution changed while it was being parked: "
                                + execution.getTaskName() + "/" + execution.getId());
            }
        });
    }

    /** Inserts the parked payload back into db-scheduler and removes it atomically. */
    public boolean requeue(ExecutionRow failed, Instant executionTime) {
        Boolean result = transaction.execute(status -> {
            String payloadValue = dialect == Dialect.SQLSERVER
                    ? "CONVERT(varbinary(max), ?)" : "?";
            int inserted = jdbc.update("INSERT INTO " + scheduledTable
                            + " (task_name, task_instance, task_data, execution_time, picked, picked_by,"
                            + " last_success, last_failure, consecutive_failures, last_heartbeat, version, priority)"
                            + " VALUES (?, ?, " + payloadValue
                            + ", ?, ?, NULL, NULL, NULL, 0, NULL, 1, ?)",
                    failed.taskName(), failed.instanceId(), failed.taskData(),
                    dialect.jdbcTimestamp(executionTime), false, failed.priority());
            if (inserted != 1) {
                throw new ConcurrencyFailureException("Failed to recreate parked execution");
            }
            int deleted = jdbc.update("DELETE FROM " + TABLE
                            + " WHERE task_name = ? AND task_instance = ?",
                    failed.taskName(), failed.instanceId());
            if (deleted != 1) {
                throw new ConcurrencyFailureException(
                        "Parked execution changed while it was being requeued");
            }
            return true;
        });
        return Boolean.TRUE.equals(result);
    }

    public boolean delete(String taskName, String instanceId) {
        if (!tableExists()) {
            return false;
        }
        return jdbc.update("DELETE FROM " + TABLE
                        + " WHERE task_name = ? AND task_instance = ?", taskName, instanceId) == 1;
    }

    private void applyFilter(ExecutionFilter filter, StringBuilder where, List<Object> params) {
        if (filter.taskName() != null && !filter.taskName().isBlank()) {
            where.append(" AND task_name = ?");
            params.add(filter.taskName());
        }
        if (filter.instanceContains() != null && !filter.instanceContains().isBlank()) {
            where.append(" AND LOWER(task_instance) LIKE ?");
            params.add("%" + filter.instanceContains().toLowerCase(Locale.ROOT) + "%");
        }
        if (filter.from() != null) {
            where.append(" AND failed_at >= ?");
            params.add(dialect.jdbcTimestamp(filter.from()));
        }
        if (filter.to() != null) {
            where.append(" AND failed_at <= ?");
            params.add(dialect.jdbcTimestamp(filter.to()));
        }
    }

    private static String failedSortColumn(SortColumn sort) {
        return switch (sort) {
            case EXECUTION_TIME, LAST_HEARTBEAT -> "failed_at";
            case TASK_NAME -> "task_name";
            case CONSECUTIVE_FAILURES -> "failure_count";
        };
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private ExecutionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ExecutionRow(
                rs.getString("task_name"),
                rs.getString("task_instance"),
                rs.getBytes("task_data"),
                instant(rs, "failed_at"),
                false,
                rs.getString("picked_by"),
                null,
                instant(rs, "failed_at"),
                rs.getInt("failure_count"),
                null,
                0,
                rs.getInt("priority"),
                true,
                rs.getString("task_data_type"),
                rs.getString("exception_class"),
                rs.getString("exception_message"),
                rs.getString("stacktrace"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        if (dialect == Dialect.SQLSERVER) {
            OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
            return value == null ? null : value.toInstant();
        }
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
