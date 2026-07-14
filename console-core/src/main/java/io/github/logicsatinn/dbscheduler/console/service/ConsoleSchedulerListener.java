package io.github.logicsatinn.dbscheduler.console.service;

import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records finished executions into dsc_execution_history.
 * MUST never propagate exceptions — a history failure must not affect task execution.
 */
public class ConsoleSchedulerListener extends AbstractSchedulerListener {

    private static final Logger LOG = LoggerFactory.getLogger(ConsoleSchedulerListener.class);

    private final HistoryRepository history;

    public ConsoleSchedulerListener(HistoryRepository history) {
        this.history = history;
    }

    @Override
    public void onExecutionComplete(ExecutionComplete complete) {
        try {
            Execution execution = complete.getExecution();
            Instant finished = complete.getTimeDone();
            Instant started = finished.minus(complete.getDuration());
            boolean ok = complete.getResult() == ExecutionComplete.Result.OK;
            Throwable cause = complete.getCause().orElse(null);
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
                    cause == null ? null : stacktraceOf(cause),
                    execution.pickedBy));
        } catch (Throwable t) {
            LOG.warn("db-scheduler-console: failed to record execution history", t);
        }
    }

    private static String stacktraceOf(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
