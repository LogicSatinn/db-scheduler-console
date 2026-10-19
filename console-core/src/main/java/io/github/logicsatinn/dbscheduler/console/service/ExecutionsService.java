package io.github.logicsatinn.dbscheduler.console.service;

import com.github.kagkarlsson.scheduler.task.Task;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionFilter;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRow;
import io.github.logicsatinn.dbscheduler.console.data.FailedExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.Page;
import io.github.logicsatinn.dbscheduler.console.data.TaskKind;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

public class ExecutionsService {

    private final ExecutionRepository live;
    private final FailedExecutionRepository failed;
    private final Map<TaskKind, List<String>> taskNamesByKind;

    public ExecutionsService(ExecutionRepository live, FailedExecutionRepository failed) {
        this(live, failed, List.of());
    }

    public ExecutionsService(ExecutionRepository live, FailedExecutionRepository failed,
            List<Task<?>> tasks) {
        this.live = live;
        this.failed = failed;
        Map<TaskKind, TreeSet<String>> byKind = new EnumMap<>(TaskKind.class);
        for (TaskKind kind : TaskKind.values()) byKind.put(kind, new TreeSet<>());
        for (Task<?> task : tasks) {
            TaskKind kind = TaskKind.of(task);
            if (kind != null) byKind.get(kind).add(task.getName());
        }
        Map<TaskKind, List<String>> names = new EnumMap<>(TaskKind.class);
        byKind.forEach((kind, set) -> names.put(kind, List.copyOf(set)));
        this.taskNamesByKind = names;
    }

    public Page<ExecutionRow> page(ExecutionFilter filter, Instant now) {
        return live.page(filter, now, failed.tableExists(),
                filter.kind() == null ? null : taskNamesByKind.get(filter.kind()));
    }

    public Optional<ExecutionRow> find(String taskName, String instanceId) {
        return live.metadata(taskName, instanceId).or(() -> failed.metadata(taskName, instanceId));
    }

    public byte[] payload(String task, String id, int maxBytes) {
        byte[] data = live.payload(task, id, maxBytes);
        return data != null ? data : failed.payload(task, id, maxBytes);
    }

    public List<String> distinctTaskNames() {
        TreeSet<String> names = new TreeSet<>(live.distinctTaskNames());
        names.addAll(failed.distinctTaskNames());
        return List.copyOf(names);
    }

}
