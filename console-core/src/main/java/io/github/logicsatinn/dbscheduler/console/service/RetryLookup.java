package io.github.logicsatinn.dbscheduler.console.service;

import com.github.kagkarlsson.scheduler.task.Task;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRow;
import io.github.logicsatinn.dbscheduler.console.data.FailedExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.InstanceKey;
import io.github.logicsatinn.dbscheduler.console.data.TaskKind;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class RetryLookup {

    public enum Option { RETRY, RERUN }

    private final ExecutionRepository live;
    private final FailedExecutionRepository failed;
    private final HistoryRepository history;
    private final Set<String> oneTimeTasks;

    public RetryLookup(ExecutionRepository live, FailedExecutionRepository failed,
            HistoryRepository history, List<Task<?>> tasks) {
        this.live = live;
        this.failed = failed;
        this.history = history;
        this.oneTimeTasks = tasks.stream()
                .filter(task -> TaskKind.of(task) == TaskKind.ONE_TIME)
                .map(Task::getName)
                .collect(Collectors.toUnmodifiableSet());
    }

    public Map<Long, Option> forHistory(List<HistoryEntry> entries) {
        List<HistoryEntry> failures = entries.stream()
                .filter(e -> e.outcome() == HistoryEntry.Outcome.FAILED)
                .toList();
        if (failures.isEmpty()) return Map.of();
        Set<InstanceKey> keys = failures.stream()
                .map(e -> new InstanceKey(e.taskName(), e.instanceId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<InstanceKey, Long> latest = history.latestIds(keys);
        Map<InstanceKey, ExecutionRow> liveRows = live.metadata(keys);
        Set<InstanceKey> parked = failed.parked(keys);
        Map<Long, Option> options = new HashMap<>();
        for (HistoryEntry e : failures) {
            var key = new InstanceKey(e.taskName(), e.instanceId());
            if (!Objects.equals(latest.get(key), e.id())) continue;
            ExecutionRow row = liveRows.get(key);
            if (parked.contains(key) || row != null && !row.picked() && row.consecutiveFailures() > 0) {
                options.put(e.id(), Option.RETRY);
            } else if (row == null && e.taskDataType() != null && oneTimeTasks.contains(e.taskName())) {
                options.put(e.id(), Option.RERUN);
            }
        }
        return options;
    }
}
