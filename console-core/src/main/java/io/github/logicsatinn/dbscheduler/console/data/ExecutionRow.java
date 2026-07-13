package io.github.logicsatinn.dbscheduler.console.data;

import java.time.Instant;

public record ExecutionRow(
        String taskName,
        String instanceId,
        byte[] taskData,
        Instant executionTime,
        boolean picked,
        String pickedBy,
        Instant lastSuccess,
        Instant lastFailure,
        int consecutiveFailures,
        Instant lastHeartbeat,
        long version) {

    public ExecutionState state(Instant now) {
        if (picked) return ExecutionState.RUNNING;
        if (consecutiveFailures > 0) return ExecutionState.FAILING;
        return executionTime.isAfter(now) ? ExecutionState.SCHEDULED : ExecutionState.DUE;
    }
}
