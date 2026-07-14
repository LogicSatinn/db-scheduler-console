package io.github.logicsatinn.dbscheduler.console.service;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class RecurringTasksService {

    private final List<Task<?>> knownTasks;
    private final ExecutionRepository executions;
    private final HistoryRepository history;
    private volatile boolean historyTableSeen;

    public RecurringTasksService(List<Task<?>> knownTasks,
            ExecutionRepository executions, HistoryRepository history) {
        this.knownTasks = knownTasks;
        this.executions = executions;
        this.history = history;
    }

    public record Row(String taskName, String type, Instant nextExecution, HistoryEntry lastRun) {}

    public List<Row> rows() {
        // Two batched queries, not two per task.
        Map<String, Instant> nextExecutions = executions.nextExecutionTimes();
        Map<String, HistoryEntry> lastRuns =
                historyAvailable() ? history.latestPerTask() : Map.of();
        return knownTasks.stream()
                .sorted(Comparator.comparing(Task::getName))
                .map(task -> new Row(
                        task.getName(),
                        typeOf(task),
                        nextExecutions.get(task.getName()),
                        lastRuns.get(task.getName())))
                .toList();
    }

    private boolean historyAvailable() {
        if (!historyTableSeen) {
            historyTableSeen = history.tableExists();
        }
        return historyTableSeen;
    }

    private static String typeOf(Task<?> task) {
        if (task instanceof RecurringTask<?>) return "Recurring";
        if (task instanceof OneTimeTask<?>) return "One-time";
        return "Custom";
    }
}
