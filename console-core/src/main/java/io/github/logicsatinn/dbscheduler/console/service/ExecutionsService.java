package io.github.logicsatinn.dbscheduler.console.service;

import io.github.logicsatinn.dbscheduler.console.data.ExecutionFilter;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRow;
import io.github.logicsatinn.dbscheduler.console.data.FailedExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.Page;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

public class ExecutionsService {

    private final ExecutionRepository live;
    private final FailedExecutionRepository failed;

    public ExecutionsService(ExecutionRepository live, FailedExecutionRepository failed) {
        this.live = live;
        this.failed = failed;
    }

    public Page<ExecutionRow> page(ExecutionFilter filter, Instant now) {
        return live.page(filter, now, failed.tableExists());
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
