package io.github.logicsatinn.dbscheduler.console.data.history;

import java.time.Instant;

public record HistoryEntry(
        long id,
        String taskName,
        String instanceId,
        Outcome outcome,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        String exceptionClass,
        String exceptionMessage,
        String stacktrace,
        String pickedBy,
        byte[] taskData,
        String taskDataType) {

    public HistoryEntry(
            long id,
            String taskName,
            String instanceId,
            Outcome outcome,
            Instant startedAt,
            Instant finishedAt,
            long durationMs,
            String exceptionClass,
            String exceptionMessage,
            String stacktrace,
            String pickedBy) {
        this(id, taskName, instanceId, outcome, startedAt, finishedAt, durationMs,
                exceptionClass, exceptionMessage, stacktrace, pickedBy, null, null);
    }

    public enum Outcome { SUCCEEDED, FAILED }
}
