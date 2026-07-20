package io.github.logicsatinn.dbscheduler.console.service;

import io.github.logicsatinn.dbscheduler.console.data.ExecutionFilter;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRow;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionState;
import io.github.logicsatinn.dbscheduler.console.data.FailedExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.Page;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/** Combines db-scheduler's live table with the console's parked-failure table. */
public class ExecutionsService {

    private final ExecutionRepository live;
    private final FailedExecutionRepository failed;

    public ExecutionsService(ExecutionRepository live, FailedExecutionRepository failed) {
        this.live = live;
        this.failed = failed;
    }

    public Page<ExecutionRow> page(ExecutionFilter filter, Instant now) {
        if (filter.state() == ExecutionState.FAILED) {
            return failed.page(filter);
        }
        if (filter.state() != null) {
            return live.page(filter, now);
        }
        int requested = Math.max(filter.pageSize(), (filter.page() + 1) * filter.pageSize());
        ExecutionFilter expanded = new ExecutionFilter(null, filter.taskName(),
                filter.instanceContains(), filter.from(), filter.to(), 0, requested,
                filter.sort(), filter.descending());
        Page<ExecutionRow> livePage = live.page(expanded, now);
        Page<ExecutionRow> failedPage = failed.page(expanded);
        List<ExecutionRow> combined = new ArrayList<>(livePage.items());
        combined.addAll(failedPage.items());
        combined.sort(comparator(filter));
        int from = Math.min(filter.page() * filter.pageSize(), combined.size());
        int to = Math.min(from + filter.pageSize(), combined.size());
        return new Page<>(List.copyOf(combined.subList(from, to)), filter.page(), filter.pageSize(),
                livePage.total() + failedPage.total());
    }

    public Optional<ExecutionRow> find(String taskName, String instanceId) {
        return live.find(taskName, instanceId).or(() -> failed.find(taskName, instanceId));
    }

    public List<String> distinctTaskNames() {
        TreeSet<String> names = new TreeSet<>(live.distinctTaskNames());
        names.addAll(failed.distinctTaskNames());
        return List.copyOf(names);
    }

    private static Comparator<ExecutionRow> comparator(ExecutionFilter filter) {
        Comparator<ExecutionRow> primary = switch (filter.sort()) {
            case EXECUTION_TIME -> Comparator.comparing(ExecutionRow::executionTime,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case TASK_NAME -> Comparator.comparing(ExecutionRow::taskName);
            case CONSECUTIVE_FAILURES -> Comparator.comparingInt(ExecutionRow::consecutiveFailures);
            case LAST_HEARTBEAT -> Comparator.comparing(ExecutionRow::lastHeartbeat,
                    Comparator.nullsLast(Comparator.naturalOrder()));
        };
        if (filter.descending()) {
            primary = primary.reversed();
        }
        return primary.thenComparing(ExecutionRow::taskName)
                .thenComparing(ExecutionRow::instanceId);
    }
}
