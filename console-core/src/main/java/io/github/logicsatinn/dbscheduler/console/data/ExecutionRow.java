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
        long version,
        int priority,
        boolean parked,
        String taskDataType,
        String exceptionClass,
        String exceptionMessage,
        String stacktrace) {

    public ExecutionRow(
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
        this(taskName, instanceId, taskData, executionTime, picked, pickedBy, lastSuccess,
                lastFailure, consecutiveFailures, lastHeartbeat, version, 0, false,
                null, null, null, null);
    }

    public ExecutionState state(Instant now) {
        if (parked) return ExecutionState.FAILED;
        if (picked) return ExecutionState.RUNNING;
        if (consecutiveFailures > 0) return ExecutionState.RETRYING;
        return executionTime.isAfter(now) ? ExecutionState.SCHEDULED : ExecutionState.DUE;
    }
}
