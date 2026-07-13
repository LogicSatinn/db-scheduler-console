package io.github.logicsatinn.dbscheduler.console.data;

import java.time.Instant;

public record ExecutionFilter(
        ExecutionState state,
        String taskName,
        String instanceContains,
        Instant from,
        Instant to,
        int page,
        int pageSize,
        SortColumn sort,
        boolean descending) {

    public static ExecutionFilter none(int page, int pageSize) {
        return new ExecutionFilter(null, null, null, null, null,
                page, pageSize, SortColumn.EXECUTION_TIME, false);
    }
}
