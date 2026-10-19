package io.github.logicsatinn.dbscheduler.console.data;

import java.time.Instant;

public record ExecutionFilter(
        ExecutionState state,
        String taskName,
        String instanceContains,
        Instant from,
        Instant to,
        String cursor,
        int pageSize,
        SortColumn sort,
        boolean descending,
        TaskKind kind) {

    public ExecutionFilter(ExecutionState state, String taskName, String instanceContains,
            Instant from, Instant to, String cursor, int pageSize, SortColumn sort,
            boolean descending) {
        this(state, taskName, instanceContains, from, to, cursor, pageSize, sort, descending, null);
    }

    public static ExecutionFilter none(String cursor, int pageSize) {
        return new ExecutionFilter(null, null, null, null, null,
                cursor, pageSize, SortColumn.EXECUTION_TIME, false);
    }
}
