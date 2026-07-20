package io.github.logicsatinn.dbscheduler.console.service;

import com.github.kagkarlsson.scheduler.task.FailureHandler;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.FailedExecutionRepository;
import javax.sql.DataSource;

/** Terminal failure handler that parks exhausted one-time executions for operator action. */
public final class FailedExecutionParking {

    private final FailedExecutionRepository repository;

    public FailedExecutionParking(DataSource dataSource) {
        this(dataSource, ExecutionRepository.DEFAULT_TABLE_NAME);
    }

    public FailedExecutionParking(DataSource dataSource, String scheduledTable) {
        this(new FailedExecutionRepository(dataSource, scheduledTable,
                Dialect.fromDataSource(dataSource).orElseThrow(() ->
                        new IllegalArgumentException("Unsupported database for failed-execution parking"))));
    }

    public FailedExecutionParking(FailedExecutionRepository repository) {
        this.repository = repository;
    }

    public <T> FailureHandler<T> failureHandler() {
        return (complete, operations) -> {
            Object data = complete.getExecution().taskInstance.getData();
            String dataType = data == null ? null : data.getClass().getName();
            repository.park(complete, dataType);
        };
    }
}
