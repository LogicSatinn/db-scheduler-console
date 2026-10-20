package io.github.logicsatinn.dbscheduler.console.service;

import com.github.kagkarlsson.scheduler.ScheduledExecution;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRow;
import io.github.logicsatinn.dbscheduler.console.data.FailedExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.InstanceKey;
import io.github.logicsatinn.dbscheduler.console.data.TaskKind;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.transaction.support.TransactionOperations;

public class ExecutionActions {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ExecutionActions.class);
    private final SchedulerClient client;
    private final Clock clock;
    private final FailedExecutionRepository failedExecutions;
    private final HistoryRepository history;
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
        this(client, clock, failedExecutions, null, serializer, tasks, transactions);
    }

    public ExecutionActions(SchedulerClient client, Clock clock,
            FailedExecutionRepository failedExecutions, HistoryRepository history,
            Serializer serializer, List<Task<?>> tasks, TransactionOperations transactions) {
        this.client = client;
        this.clock = clock;
        this.failedExecutions = failedExecutions;
        this.history = history;
        this.serializer = serializer;
        this.tasksByName = tasks.stream().collect(Collectors.toUnmodifiableMap(
                Task::getName, Function.identity(), (first, duplicate) -> first));
        this.transactions = transactions;
    }

    public record ActionResult(boolean success, String message) {}

    public enum Kind { RUN_NOW, RETRY, DELETE }

    private record Attempt(ActionResult result, boolean skipped) {}

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

    public ActionResult retry(String taskName, String instanceId) {
        return attemptRetry(taskName, instanceId).result();
    }

    private Attempt attemptRetry(String taskName, String instanceId) {
        try {
            if (failedExecutions != null) {
                var failed = failedExecutions.find(taskName, instanceId);
                if (failed.isPresent()) {
                    return new Attempt(requeue(failed.get()), false);
                }
            }
            Optional<ScheduledExecution<Object>> live =
                    client.getScheduledExecution(TaskInstanceId.of(taskName, instanceId));
            if (live.isEmpty()) {
                return new Attempt(new ActionResult(false, "Execution no longer exists"), false);
            }
            if (live.get().isPicked()) {
                return new Attempt(new ActionResult(false, "Retry failed for " + instanceId
                        + " — it is executing right now"), false);
            }
            if (live.get().getConsecutiveFailures() == 0) {
                return new Attempt(new ActionResult(false, "Retry skipped for " + instanceId
                        + " — it has not failed"), true);
            }
            return new Attempt(rescheduleTo(taskName, instanceId, clock.instant(), "Retry"), false);
        } catch (RuntimeException e) {
            return new Attempt(unexpected("Retry", e), false);
        }
    }

    public ActionResult rerun(long historyId) {
        if (history == null) {
            return new ActionResult(false, "Re-run needs the execution history table");
        }
        try {
            Optional<HistoryEntry> found = history.find(historyId, FilteringJavaSerializer.MAX_INPUT_BYTES);
            if (found.isEmpty()) {
                return new ActionResult(false, "History entry no longer exists");
            }
            HistoryEntry entry = found.get();
            String id = entry.instanceId();
            if (entry.outcome() != HistoryEntry.Outcome.FAILED) {
                return new ActionResult(false, "Re-run is only available for failed runs");
            }
            Task<?> task = tasksByName.get(entry.taskName());
            if (task == null || TaskKind.of(task) != TaskKind.ONE_TIME) {
                return new ActionResult(false, "Re-run failed for " + id
                        + ": no registered one-time task named " + entry.taskName());
            }
            if (entry.taskDataType() == null) {
                return new ActionResult(false, "Re-run failed for " + id + ": its payload was not recorded"
                        + " (enable db-scheduler-console.history.store-task-data)");
            }
            var key = new InstanceKey(entry.taskName(), id);
            if (!Objects.equals(history.latestIds(Set.of(key)).get(key), historyId)) {
                return new ActionResult(false, "Re-run failed for " + id
                        + " — it has run again since this failure");
            }
            if (failedExecutions != null && failedExecutions.metadata(entry.taskName(), id).isPresent()) {
                return new ActionResult(false, "Re-run failed for " + id
                        + " — it is parked; use Retry instead");
            }
            if (client.getScheduledExecution(TaskInstanceId.of(entry.taskName(), id)).isPresent()) {
                return alreadyScheduled(id);
            }
            ActionResult rejected = payloadRejection("Re-run", entry.taskData());
            if (rejected != null) {
                return rejected;
            }
            TaskInstance<?> instance = instance(task, id,
                    serializer.deserialize(task.getDataClass(), entry.taskData()));
            Boolean scheduled = transactions.execute(status ->
                    client.scheduleIfNotExists(instance, clock.instant()));
            return Boolean.TRUE.equals(scheduled)
                    ? new ActionResult(true, "Re-run scheduled for " + id)
                    : alreadyScheduled(id);
        } catch (RuntimeException e) {
            return unexpected("Re-run", e);
        }
    }

    private static ActionResult alreadyScheduled(String instanceId) {
        return new ActionResult(false, "Re-run failed for " + instanceId
                + " — it is already scheduled; use Retry instead");
    }

    @SuppressWarnings("unchecked")
    private static <T> TaskInstance<T> instance(Task<T> task, String id, Object data) {
        return task.instance(id, (T) data);
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
            ActionResult rejected = payloadRejection("Requeue", failed.taskData());
            if (rejected != null) return rejected;
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

    private ActionResult payloadRejection(String operation, byte[] data) {
        if (data != null && data.length > FilteringJavaSerializer.MAX_INPUT_BYTES)
            return new ActionResult(false, operation + " rejected: payload exceeds input limit");
        if (data != null && serializer.getClass() == com.github.kagkarlsson.scheduler.serializer.JavaSerializer.class
                && java.io.ObjectInputFilter.Config.getSerialFilter() == null)
            return new ActionResult(false, operation + " requires a host-configured Java deserialization filter");
        return null;
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
        int skipped = 0;
        for (InstanceRef ref : refs) {
            Attempt a = switch (kind) {
                case RUN_NOW -> new Attempt(runNow(ref.taskName(), ref.instanceId()), false);
                case RETRY -> attemptRetry(ref.taskName(), ref.instanceId());
                case DELETE -> new Attempt(delete(ref.taskName(), ref.instanceId()), false);
            };
            if (a.skipped()) skipped++; else if (a.result().success()) succeeded++; else failed++;
        }
        String label = switch (kind) {
            case RUN_NOW -> "Run now";
            case RETRY -> "Retry";
            case DELETE -> "Delete";
        };
        return new ActionResult(failed == 0,
                label + ": " + succeeded + " succeeded, " + failed + " failed"
                        + (skipped > 0 ? ", " + skipped + " skipped (not failed)" : ""));
    }
}
