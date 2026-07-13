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
        String pickedBy) {

    public enum Outcome { SUCCEEDED, FAILED }
}
