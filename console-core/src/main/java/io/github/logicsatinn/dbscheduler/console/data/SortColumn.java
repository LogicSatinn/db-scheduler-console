package io.github.logicsatinn.dbscheduler.console.data;

/** Whitelisted sortable columns — the enum is the SQL-injection guard. */
public enum SortColumn {
    EXECUTION_TIME("execution_time"),
    TASK_NAME("task_name"),
    CONSECUTIVE_FAILURES("consecutive_failures"),
    LAST_HEARTBEAT("last_heartbeat");

    private final String column;

    SortColumn(String column) { this.column = column; }

    public String column() { return column; }
}
