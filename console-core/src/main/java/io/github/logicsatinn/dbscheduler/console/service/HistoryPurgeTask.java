package io.github.logicsatinn.dbscheduler.console.service;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import io.github.logicsatinn.dbscheduler.console.ConsoleAvailability;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Daily recurring db-scheduler task that enforces history retention. */
public final class HistoryPurgeTask {

    public static final String NAME = "console-history-purge";

    private static final Logger LOG = LoggerFactory.getLogger(HistoryPurgeTask.class);

    private HistoryPurgeTask() {}

    public static RecurringTask<Void> create(HistoryRepository history,
            ConsoleAvailability availability, Duration retention, Clock clock) {
        return Tasks.recurring(NAME, FixedDelay.ofHours(24))
                .execute((instance, ctx) -> purge(history, availability, retention, clock));
    }

    /**
     * The history table is optional and may be created after the app first starts, so the
     * check is repeated on every daily run rather than cached.
     */
    private static void purge(HistoryRepository history, ConsoleAvailability availability,
            Duration retention, Clock clock) {
        if (!availability.available()) {
            LOG.warn("db-scheduler-console: unsupported database — skipping history purge.");
            return;
        }
        if (!history.tableExists()) {
            LOG.warn("db-scheduler-console: history table {} not found — skipping purge;"
                    + " apply the migration from db-scheduler-console/migrations/",
                    HistoryRepository.TABLE);
            return;
        }
        history.purgeOlderThan(clock.instant().minus(retention));
    }
}
