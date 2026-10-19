package io.github.logicsatinn.dbscheduler.console.data;

import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class FailedExecutionRepository {

    public static final String TABLE = "dsc_failed_execution";

    private static final int MAX_MESSAGE = 2000;
    private static final int MAX_STACKTRACE = 30000;
    private static final String COLUMNS =
            "task_name, task_instance, task_data, task_data_type, priority, failure_count, "
            + "failed_at, picked_by, exception_class, exception_message, stacktrace";

    private final DiagnosticSanitizer sanitizer;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final String scheduledTable;
    private final Dialect dialect;

    public FailedExecutionRepository(DataSource dataSource, String scheduledTable, Dialect dialect) {
        this(dataSource, scheduledTable, dialect, DiagnosticSanitizer.IDENTITY);
    }

    public FailedExecutionRepository(DataSource dataSource, String scheduledTable, Dialect dialect,
            DiagnosticSanitizer sanitizer) {
        this.sanitizer = sanitizer;
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
        Keyset.size(filter.pageSize());
        if (filter.state() != null && filter.state() != ExecutionState.FAILED)
            return new Page<>(List.of(), null, null);
        ExecutionFilter failed = new ExecutionFilter(ExecutionState.FAILED, filter.taskName(),
                filter.instanceContains(), filter.from(), filter.to(), filter.cursor(),
                filter.pageSize(), filter.sort(), filter.descending());
        return new ExecutionRepository(jdbc.getDataSource(), scheduledTable, dialect)
                .page(failed, Instant.now(), tableExists());
    }

    public byte[] payload(String task, String id, int maxBytes) {
        if (!tableExists()) return null;
        return jdbc.query("SELECT task_data FROM " + TABLE + " WHERE task_name = ? AND task_instance = ?", rs -> {
            if (!rs.next()) return null;
            try (var in = rs.getBinaryStream(1)) {
                return in == null ? null : in.readNBytes(maxBytes + 1);
            } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        }, task, id);
    }

    public Optional<ExecutionRow> metadata(String task, String id) {
        if (!tableExists()) return Optional.empty();
        return jdbc.query("SELECT " + COLUMNS.replace("task_data,", "NULL AS task_data,") + " FROM " + TABLE
                + " WHERE task_name = ? AND task_instance = ?", this::mapRow, task, id).stream().findFirst();
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
                    cause == null ? null : truncate(DiagnosticSanitizer.apply(sanitizer, cause.getMessage()), MAX_MESSAGE),
                    cause == null ? null : truncate(DiagnosticSanitizer.apply(sanitizer, Stacktraces.render(cause)), MAX_STACKTRACE),
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
        Integer deleted = transaction.execute(status -> jdbc.update("DELETE FROM " + TABLE
                + " WHERE task_name = ? AND task_instance = ?", taskName, instanceId));
        return deleted != null && deleted == 1;
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
                DiagnosticSanitizer.apply(sanitizer, rs.getString("exception_message")),
                DiagnosticSanitizer.apply(sanitizer, rs.getString("stacktrace")));
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
