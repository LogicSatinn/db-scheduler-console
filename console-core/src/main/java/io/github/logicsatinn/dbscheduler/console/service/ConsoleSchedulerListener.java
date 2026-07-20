package io.github.logicsatinn.dbscheduler.console.service;

import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.Task;
import io.github.logicsatinn.dbscheduler.console.data.Stacktraces;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records finished executions into dsc_execution_history.
 * MUST never propagate exceptions — a history failure must not affect task execution.
 */
public class ConsoleSchedulerListener extends AbstractSchedulerListener {

    private static final Logger LOG = LoggerFactory.getLogger(ConsoleSchedulerListener.class);

    private final HistoryRepository history;
    private final Supplier<Serializer> serializer;
    private final Supplier<List<Task<?>>> tasks;
    private final boolean storeTaskData;
    private final HistoryFailureReporter failureReporter;

    public ConsoleSchedulerListener(HistoryRepository history) {
        this(history, () -> Serializer.DEFAULT_JAVA_SERIALIZER, List::of, false,
                HistoryFailureReporter.NOOP);
    }

    public ConsoleSchedulerListener(HistoryRepository history, Serializer serializer,
            List<com.github.kagkarlsson.scheduler.task.Task<?>> tasks, boolean storeTaskData,
            HistoryFailureReporter failureReporter) {
        this(history, () -> serializer, () -> tasks, storeTaskData, failureReporter);
    }

    public ConsoleSchedulerListener(HistoryRepository history, Supplier<Serializer> serializer,
            boolean storeTaskData, HistoryFailureReporter failureReporter) {
        this(history, serializer, List::of, storeTaskData, failureReporter);
    }

    public ConsoleSchedulerListener(HistoryRepository history, Supplier<Serializer> serializer,
            Supplier<List<Task<?>>> tasks, boolean storeTaskData,
            HistoryFailureReporter failureReporter) {
        this.history = history;
        this.serializer = serializer;
        this.tasks = tasks;
        this.storeTaskData = storeTaskData;
        this.failureReporter = failureReporter;
    }

    @Override
    public void onExecutionComplete(ExecutionComplete complete) {
        try {
            Execution execution = complete.getExecution();
            Instant finished = complete.getTimeDone();
            Instant started = finished.minus(complete.getDuration());
            boolean ok = complete.getResult() == ExecutionComplete.Result.OK;
            Throwable cause = complete.getCause().orElse(null);
            Payload payload = payload(execution);
            history.insert(new HistoryEntry(
                    0,
                    execution.getTaskName(),
                    execution.getId(),
                    ok ? HistoryEntry.Outcome.SUCCEEDED : HistoryEntry.Outcome.FAILED,
                    started,
                    finished,
                    complete.getDuration().toMillis(),
                    cause == null ? null : cause.getClass().getName(),
                    cause == null ? null : cause.getMessage(),
                    cause == null ? null : Stacktraces.render(cause),
                    execution.pickedBy,
                    payload.data(),
                    payload.type()));
        } catch (Throwable t) {
            LOG.warn("db-scheduler-console: failed to record execution history", t);
            reportFailure(t);
        }
    }

    private Payload payload(Execution execution) {
        if (!storeTaskData || history.schema() != HistoryRepository.Schema.CURRENT) {
            return Payload.EMPTY;
        }
        try {
            Object data = execution.taskInstance.getData();
            String type = tasks.get().stream()
                    .filter(task -> task.getName().equals(execution.getTaskName()))
                    .map(task -> task.getDataClass().getName())
                    .findFirst()
                    .orElseGet(() -> data == null ? null : data.getClass().getName());
            return new Payload(serializer.get().serialize(data), type);
        } catch (Throwable failure) {
            LOG.warn("db-scheduler-console: failed to serialize task data for history;"
                    + " recording metadata only for {}/{}",
                    execution.getTaskName(), execution.getId(), failure);
            reportFailure(failure);
            return Payload.EMPTY;
        }
    }

    private void reportFailure(Throwable failure) {
        try {
            failureReporter.historyWriteFailed(failure);
        } catch (Throwable reporterFailure) {
            LOG.debug("db-scheduler-console: history failure reporter failed", reporterFailure);
        }
    }

    private record Payload(byte[] data, String type) {
        private static final Payload EMPTY = new Payload(null, null);
    }
}
