package io.github.logicsatinn.dbscheduler.console.boot4;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerProperties;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import io.github.logicsatinn.dbscheduler.console.ConsoleAvailability;
import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.FailedExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.ConsoleSchedulerListener;
import io.github.logicsatinn.dbscheduler.console.service.FailedExecutionParking;
import io.github.logicsatinn.dbscheduler.console.service.HistoryFailureReporter;
import io.github.logicsatinn.dbscheduler.console.service.HistoryPurgeTask;
import io.github.logicsatinn.dbscheduler.console.service.MicrometerHistoryFailureReporter;
import java.time.Clock;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Infrastructure that must exist before db-scheduler assembles tasks and listeners. */
@AutoConfiguration(
        afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        beforeName = "com.github.kagkarlsson.scheduler.boot.autoconfigure.DbSchedulerAutoConfiguration")
@ConditionalOnClass(Scheduler.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "db-scheduler-console", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ConsoleProperties.class)
public class DbSchedulerConsoleInfrastructureAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(
            DbSchedulerConsoleInfrastructureAutoConfiguration.class);
    private static final Clock CLOCK = Clock.systemUTC();

    @Bean
    ConsoleAvailability dbSchedulerConsoleAvailability(DataSource dataSource) {
        var dialect = Dialect.fromDataSource(dataSource).orElse(null);
        if (dialect == null) {
            LOG.error("db-scheduler-console: unsupported database — the dashboard is disabled."
                    + " Supported: PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, H2.");
        }
        return new ConsoleAvailability(dialect);
    }

    @Bean
    ExecutionRepository dbSchedulerConsoleExecutionRepository(DataSource dataSource,
            ObjectProvider<DbSchedulerProperties> dbSchedulerProperties,
            ConsoleAvailability availability) {
        String tableName = dbSchedulerProperties.stream()
                .map(DbSchedulerProperties::getTableName)
                .findFirst()
                .orElse(ExecutionRepository.DEFAULT_TABLE_NAME);
        return new ExecutionRepository(dataSource, tableName,
                availability.dialectOrFallback());
    }

    @Bean
    HistoryRepository dbSchedulerConsoleHistoryRepository(DataSource dataSource,
            ConsoleAvailability availability) {
        return new HistoryRepository(dataSource, availability.dialectOrFallback());
    }

    @Bean
    FailedExecutionRepository dbSchedulerConsoleFailedExecutionRepository(DataSource dataSource,
            ExecutionRepository executions, ConsoleAvailability availability) {
        return new FailedExecutionRepository(dataSource, executions.tableName(),
                availability.dialectOrFallback());
    }

    @Bean
    @ConditionalOnMissingBean
    FailedExecutionParking dbSchedulerConsoleFailedExecutionParking(
            FailedExecutionRepository failedExecutions) {
        return new FailedExecutionParking(failedExecutions);
    }

    @Bean
    @ConditionalOnMissingBean
    HistoryFailureReporter dbSchedulerConsoleHistoryFailureReporter(ListableBeanFactory beans) {
        return MicrometerHistoryFailureReporter.create(beans);
    }

    @Bean
    @ConditionalOnProperty(prefix = "db-scheduler-console.history", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    ConsoleSchedulerListener dbSchedulerConsoleSchedulerListener(HistoryRepository history,
            ConsoleProperties props,
            HistoryFailureReporter failureReporter,
            ObjectProvider<Task<?>> tasks,
            ObjectProvider<DbSchedulerCustomizer> customizer) {
        return new ConsoleSchedulerListener(history, () -> effectiveSerializer(customizer),
                () -> tasks.orderedStream().toList(),
                props.getHistory().isStoreTaskData(), failureReporter);
    }

    @Bean
    @ConditionalOnProperty(prefix = "db-scheduler-console.history", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    RecurringTask<Void> dbSchedulerConsoleHistoryPurgeTask(HistoryRepository history,
            ConsoleAvailability availability, ConsoleProperties props) {
        return HistoryPurgeTask.create(
                history, availability, props.getHistory().getRetention(), CLOCK);
    }

    private static Serializer effectiveSerializer(ObjectProvider<DbSchedulerCustomizer> customizer) {
        return customizer.stream()
                .flatMap(candidate -> candidate.serializer().stream())
                .findFirst()
                .orElse(Serializer.DEFAULT_JAVA_SERIALIZER);
    }
}
