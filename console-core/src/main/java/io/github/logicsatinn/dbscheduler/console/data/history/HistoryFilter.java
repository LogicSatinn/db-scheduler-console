package io.github.logicsatinn.dbscheduler.console.data.history;

import java.time.Instant;

public record HistoryFilter(
        String taskName,
        HistoryEntry.Outcome outcome,
        String textSearch,
        Instant from,
        Instant to,
        int page,
        int pageSize) {

    public static HistoryFilter none(int page, int pageSize) {
        return new HistoryFilter(null, null, null, null, null, page, pageSize);
    }
}
