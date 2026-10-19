package io.github.logicsatinn.dbscheduler.console.data;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTaskWithPersistentSchedule;

public enum TaskKind {
    RECURRING("Recurring"),
    ONE_TIME("One-time");

    private final String label;

    TaskKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static TaskKind of(Task<?> task) {
        if (task instanceof RecurringTask<?> || task instanceof RecurringTaskWithPersistentSchedule<?>)
            return RECURRING;
        if (task instanceof OneTimeTask<?>) return ONE_TIME;
        return null;
    }
}
