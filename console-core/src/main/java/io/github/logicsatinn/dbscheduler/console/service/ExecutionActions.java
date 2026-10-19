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
import org.springframework.transaction.support.TransactionOperations;

public class ExecutionActions {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ExecutionActions.class);
    private final SchedulerClient client;
    private final Clock clock;
    private final FailedExecutionRepository failedExecutions;
    private final Serializer serializer;
    private final Map<String, Task<?>> tasksByName;
    private final TransactionOperations transactions;

    public ExecutionActions(SchedulerClient client, Clock clock) {
        this(client, clock, null, Serializer.DEFAULT_JAVA_SERIALIZER, List.of());
    }

    public ExecutionActions(SchedulerClient client, Clock clock,
            FailedExecutionRepository failedExecutions, Serializer serializer,
            List<Task<?>> tasks) {
        this(client, clock, failedExecutions, serializer, tasks,
                TransactionOperations.withoutTransaction());
    }

    public ExecutionActions(SchedulerClient client, Clock clock,
            FailedExecutionRepository failedExecutions, Serializer serializer,
            List<Task<?>> tasks, TransactionOperations transactions) {
        this.client = client;
        this.clock = clock;
        this.failedExecutions = failedExecutions;
        this.serializer = serializer;
        this.tasksByName = tasks.stream().collect(Collectors.toUnmodifiableMap(
                Task::getName, Function.identity(), (first, duplicate) -> first));
        this.transactions = transactions;
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
            if (i <= 0 || i + SEP.length() >= composite.length()) {
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
            Boolean ok = transactions.execute(status ->
                    client.reschedule(TaskInstanceId.of(taskName, instanceId), when));
            return Boolean.TRUE.equals(ok)
                    ? new ActionResult(true, verb + " succeeded for " + instanceId)
                    : new ActionResult(false, verb + " failed for " + instanceId
                            + " — it is executing right now, was changed concurrently, or no longer exists");
        } catch (com.github.kagkarlsson.scheduler.exceptions.TaskInstanceNotFoundException e) {
            return new ActionResult(false, "Execution no longer exists");
        } catch (RuntimeException e) {
            return unexpected(verb, e);
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
            transactions.executeWithoutResult(status ->
                    client.cancel(TaskInstanceId.of(taskName, instanceId)));
            return new ActionResult(true, "Deleted " + instanceId);
        } catch (com.github.kagkarlsson.scheduler.exceptions.TaskInstanceNotFoundException e) {
            return new ActionResult(false, "Execution no longer exists");
        } catch (RuntimeException e) {
            return unexpected("Delete", e);
        }
    }

    private ActionResult requeue(ExecutionRow failed) {
        Task<?> task = tasksByName.get(failed.taskName());
        if (task == null) {
            return new ActionResult(false, "Requeue failed for " + failed.instanceId()
                    + ": no registered task named " + failed.taskName());
        }
        try {
            if (failed.taskData() != null && failed.taskData().length > FilteringJavaSerializer.MAX_INPUT_BYTES)
                return new ActionResult(false, "Requeue rejected: payload exceeds input limit");
            if (failed.taskData() != null && serializer.getClass() == com.github.kagkarlsson.scheduler.serializer.JavaSerializer.class
                    && java.io.ObjectInputFilter.Config.getSerialFilter() == null)
                return new ActionResult(false, "Requeue requires a host-configured Java deserialization filter");
            serializer.deserialize(task.getDataClass(), failed.taskData());
            boolean requeued = failedExecutions.requeue(failed, clock.instant());
            return requeued
                    ? new ActionResult(true, "Requeued " + failed.instanceId())
                    : new ActionResult(false, "Requeue failed for " + failed.instanceId()
                            + " — it was changed concurrently or no longer exists");
        } catch (RuntimeException e) {
            return unexpected("Requeue", e);
        }
    }

    private static ActionResult unexpected(String operation, RuntimeException error) {
        String reference = java.util.UUID.randomUUID().toString();
        LOG.warn("Console action failed: operation={}, reference={}, exceptionType={}",
                operation, reference, error.getClass().getName());
        return new ActionResult(false, operation + " failed. Reference: " + reference);
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
