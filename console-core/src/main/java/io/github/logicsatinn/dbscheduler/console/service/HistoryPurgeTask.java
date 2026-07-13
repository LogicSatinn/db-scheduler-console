package io.github.logicsatinn.dbscheduler.console.service;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.time.Clock;
import java.time.Duration;

/** Daily recurring db-scheduler task that enforces history retention. */
public final class HistoryPurgeTask {

    public static final String NAME = "console-history-purge";

    private HistoryPurgeTask() {}

    public static RecurringTask<Void> create(HistoryRepository history, Duration retention, Clock clock) {
        return Tasks.recurring(NAME, FixedDelay.ofHours(24))
                .execute((instance, ctx) ->
                        history.purgeOlderThan(clock.instant().minus(retention)));
    }
}
