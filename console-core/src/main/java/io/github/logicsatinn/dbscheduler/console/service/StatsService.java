package io.github.logicsatinn.dbscheduler.console.service;

import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public class StatsService {

    private static final int WINDOW_CAP = 200_000;

    private final ExecutionRepository executions;
    private final HistoryRepository history;
    private final Clock clock;
    private volatile boolean historyTableSeen;

    public StatsService(ExecutionRepository executions, HistoryRepository history, Clock clock) {
        this.executions = executions;
        this.history = history;
        this.clock = clock;
    }

    public record Tiles(long scheduled, long due, long running, long failing,
                        long succeeded24h, long failed24h) {}

    public record HourBucket(Instant hourStart, long succeeded, long failed) {}

    public boolean historyAvailable() {
        if (!historyTableSeen) {
            historyTableSeen = history.tableExists();
        }
        return historyTableSeen;
    }

    public Tiles tiles() {
        Instant now = clock.instant();
        var live = executions.counts(now);
        var outcomes = historyAvailable()
                ? history.countsSince(now.minus(24, ChronoUnit.HOURS))
                : new HistoryRepository.OutcomeCounts(0, 0);
        return new Tiles(live.scheduled(), live.due(), live.running(), live.failing(),
                outcomes.succeeded(), outcomes.failed());
    }

    /** 24 hour-aligned buckets, oldest first; empty list if history is unavailable. */
    public List<HourBucket> throughputLast24h() {
        if (!historyAvailable()) {
            return List.of();
        }
        Instant end = clock.instant().truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS);
        Instant start = end.minus(24, ChronoUnit.HOURS);
        long[] succeeded = new long[24];
        long[] failed = new long[24];
        for (var p : history.window(start, end, WINDOW_CAP)) {
            int idx = (int) ChronoUnit.HOURS.between(start, p.startedAt());
            if (idx < 0 || idx > 23) {
                continue;
            }
            if (p.outcome() == HistoryEntry.Outcome.SUCCEEDED) {
                succeeded[idx]++;
            } else {
                failed[idx]++;
            }
        }
        List<HourBucket> buckets = new ArrayList<>(24);
        for (int i = 0; i < 24; i++) {
            buckets.add(new HourBucket(start.plus(i, ChronoUnit.HOURS), succeeded[i], failed[i]));
        }
        return buckets;
    }
}
