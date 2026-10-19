package io.github.logicsatinn.dbscheduler.console.data.history;

import java.time.Instant;

public record HistoryFilter(
        String taskName,
        HistoryEntry.Outcome outcome,
        String textSearch,
        Instant from,
        Instant to,
        String cursor,
        int pageSize) {

    public static HistoryFilter none(String cursor, int pageSize) {
        return new HistoryFilter(null, null, null, null, null, cursor, pageSize);
    }
}
