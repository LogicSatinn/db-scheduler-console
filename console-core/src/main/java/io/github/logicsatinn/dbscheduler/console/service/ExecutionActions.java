package io.github.logicsatinn.dbscheduler.console.service;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** All mutations go through SchedulerClient so optimistic locking is respected. */
public class ExecutionActions {

    private final SchedulerClient client;
    private final Clock clock;

    public ExecutionActions(SchedulerClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
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
            client.cancel(TaskInstanceId.of(taskName, instanceId));
            return new ActionResult(true, "Deleted " + instanceId);
        } catch (RuntimeException e) {
            return new ActionResult(false, "Delete failed for " + instanceId
                    + " — it is executing right now or no longer exists");
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
