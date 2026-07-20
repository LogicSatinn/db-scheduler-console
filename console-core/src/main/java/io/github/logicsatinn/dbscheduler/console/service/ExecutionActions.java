package io.github.logicsatinn.dbscheduler.console.service;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRow;
import io.github.logicsatinn.dbscheduler.console.data.FailedExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** All mutations go through SchedulerClient so optimistic locking is respected. */
public class ExecutionActions {

    private final SchedulerClient client;
    private final Clock clock;
    private final FailedExecutionRepository failedExecutions;
    private final Serializer serializer;
    private final Map<String, Task<?>> tasksByName;

    public ExecutionActions(SchedulerClient client, Clock clock) {
        this(client, clock, null, Serializer.DEFAULT_JAVA_SERIALIZER, List.of());
    }

    public ExecutionActions(SchedulerClient client, Clock clock,
            FailedExecutionRepository failedExecutions, Serializer serializer,
            List<Task<?>> tasks) {
        this.client = client;
        this.clock = clock;
        this.failedExecutions = failedExecutions;
        this.serializer = serializer;
        this.tasksByName = tasks.stream().collect(Collectors.toUnmodifiableMap(
                Task::getName, Function.identity(), (first, duplicate) -> first));
    }

    public record ActionResult(boolean success, String message) {}

    public enum Kind { RUN_NOW, DELETE }

    public record InstanceRef(String taskName, String instanceId) {
        private static final String SEP = "::";

        public String composite() {
            return taskName + SEP + instanceId;
        }

        public static InstanceRef parse(String composite) {
            int i = composite.indexOf(SEP);
            if (i < 0) {
                throw new IllegalArgumentException("Malformed instance ref");
            }
            return new InstanceRef(composite.substring(0, i), composite.substring(i + SEP.length()));
        }
    }

    public ActionResult runNow(String taskName, String instanceId) {
        if (failedExecutions != null) {
            var failed = failedExecutions.find(taskName, instanceId);
            if (failed.isPresent()) {
                return requeue(failed.get());
            }
        }
        return rescheduleTo(taskName, instanceId, clock.instant(), "Run now");
    }

    public ActionResult reschedule(String taskName, String instanceId, Instant when) {
        return rescheduleTo(taskName, instanceId, when, "Reschedule");
    }

    private ActionResult rescheduleTo(String taskName, String instanceId, Instant when, String verb) {
        try {
            boolean ok = client.reschedule(TaskInstanceId.of(taskName, instanceId), when);
            return ok
                    ? new ActionResult(true, verb + " succeeded for " + instanceId)
                    : new ActionResult(false, verb + " failed for " + instanceId
                            + " — it is executing right now, was changed concurrently, or no longer exists");
        } catch (RuntimeException e) {
            return new ActionResult(false, verb + " failed for " + instanceId + ": " + e.getMessage());
        }
    }

    public ActionResult delete(String taskName, String instanceId) {
        try {
            if (failedExecutions != null && failedExecutions.find(taskName, instanceId).isPresent()) {
                return failedExecutions.delete(taskName, instanceId)
                        ? new ActionResult(true, "Deleted parked execution " + instanceId)
                        : new ActionResult(false,
                                "Delete failed for parked execution " + instanceId
                                        + " — it was changed concurrently or no longer exists");
            }
            client.cancel(TaskInstanceId.of(taskName, instanceId));
            return new ActionResult(true, "Deleted " + instanceId);
        } catch (RuntimeException e) {
            return new ActionResult(false, "Delete failed for " + instanceId
                    + " — it is executing right now or no longer exists");
        }
    }

    private ActionResult requeue(ExecutionRow failed) {
        Task<?> task = tasksByName.get(failed.taskName());
        if (task == null) {
            return new ActionResult(false, "Requeue failed for " + failed.instanceId()
                    + ": no registered task named " + failed.taskName());
        }
        try {
            serializer.deserialize(task.getDataClass(), failed.taskData());
            boolean requeued = failedExecutions.requeue(failed, clock.instant());
            return requeued
                    ? new ActionResult(true, "Requeued " + failed.instanceId())
                    : new ActionResult(false, "Requeue failed for " + failed.instanceId()
                            + " — it was changed concurrently or no longer exists");
        } catch (RuntimeException e) {
            return new ActionResult(false, "Requeue failed for " + failed.instanceId()
                    + ": " + e.getMessage());
        }
    }

    public ActionResult bulk(Kind kind, List<InstanceRef> refs) {
        int succeeded = 0;
        int failed = 0;
        for (InstanceRef ref : refs) {
            ActionResult r = switch (kind) {
                case RUN_NOW -> runNow(ref.taskName(), ref.instanceId());
                case DELETE -> delete(ref.taskName(), ref.instanceId());
            };
            if (r.success()) succeeded++; else failed++;
        }
        String label = switch (kind) {
            case RUN_NOW -> "Run now";
            case DELETE -> "Delete";
        };
        return new ActionResult(failed == 0,
                label + ": " + succeeded + " succeeded, " + failed + " failed");
    }
}
