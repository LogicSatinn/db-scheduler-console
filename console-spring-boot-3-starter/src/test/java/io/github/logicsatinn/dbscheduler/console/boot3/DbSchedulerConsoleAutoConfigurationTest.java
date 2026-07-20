package io.github.logicsatinn.dbscheduler.console.boot3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.boot.autoconfigure.DbSchedulerAutoConfiguration;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerProperties;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import io.github.logicsatinn.dbscheduler.console.ConsoleAvailability;
import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.service.ConsoleSchedulerListener;
import io.github.logicsatinn.dbscheduler.console.service.ExecutionActions;
import io.github.logicsatinn.dbscheduler.console.web.ActionsController;
import io.github.logicsatinn.dbscheduler.console.web.ExecutionsController;
import io.github.logicsatinn.dbscheduler.console.web.HistoryController;
import io.github.logicsatinn.dbscheduler.console.web.OverviewController;
import io.github.logicsatinn.dbscheduler.console.web.RecurringController;
import io.github.logicsatinn.dbscheduler.console.web.StaticAssetsController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class DbSchedulerConsoleAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    DbSchedulerAutoConfiguration.class,
                    DbSchedulerConsoleInfrastructureAutoConfiguration.class,
                    DbSchedulerConsoleAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:autoconf;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver");

    @Test
    void registersEverythingByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(OverviewController.class);
            assertThat(ctx).hasSingleBean(ExecutionsController.class);
            assertThat(ctx).hasSingleBean(RecurringController.class);
            assertThat(ctx).hasSingleBean(HistoryController.class);
            assertThat(ctx).hasSingleBean(ActionsController.class);
            assertThat(ctx).hasSingleBean(StaticAssetsController.class);
            assertThat(ctx).hasSingleBean(ExecutionActions.class);
            assertThat(ctx).hasSingleBean(ConsoleSchedulerListener.class);
            assertThat(ctx.getBeansOfType(RecurringTask.class))
                    .containsKey("dbSchedulerConsoleHistoryPurgeTask");
            assertThat(ctx.getBean(ExecutionRepository.class).tableName())
                    .isEqualTo("scheduled_tasks");
        });
    }

    @Test
    void honorsCustomTableName() {
        runner.withPropertyValues("db-scheduler.table-name=my_tasks").run(ctx ->
                assertThat(ctx.getBean(ExecutionRepository.class).tableName()).isEqualTo("my_tasks"));
    }

    @Test
    void masterSwitchDisables() {
        runner.withPropertyValues("db-scheduler-console.enabled=false").run(ctx ->
                assertThat(ctx).doesNotHaveBean(OverviewController.class));
    }

    @Test
    void historyToggleDisablesListenerAndPurge() {
        runner.withPropertyValues("db-scheduler-console.history.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(ConsoleSchedulerListener.class);
            assertThat(ctx.getBeansOfType(RecurringTask.class)).isEmpty();
            assertThat(ctx).hasSingleBean(OverviewController.class); // live views still on
        });
    }

    @Test
    void backsOffWithoutScheduler() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        DbSchedulerConsoleInfrastructureAutoConfiguration.class,
                        DbSchedulerConsoleAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:noscheduler;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(OverviewController.class));
    }

    @Test
    void historyPurgeTaskIsHarmlessWithoutTheHistoryMigration() {
        runner.run(ctx -> {
            @SuppressWarnings("unchecked")
            RecurringTask<Void> purge = ctx.getBean(
                    "dbSchedulerConsoleHistoryPurgeTask", RecurringTask.class);
            assertThatCode(() -> purge.execute(purge.instance(RecurringTask.INSTANCE), null))
                    .doesNotThrowAnyException();
        });
    }

    @Test
    void backsOffWithoutDataSource() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DbSchedulerConsoleAutoConfiguration.class))
                .withUserConfiguration(OwnSchedulerConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(ConsoleAvailability.class);
                    assertThat(ctx).doesNotHaveBean(ExecutionRepository.class);
                    assertThat(ctx).doesNotHaveBean(OverviewController.class);
                });
    }

    @Test
    void activeWithoutDbSchedulerPropertiesUsingDefaultTableName() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        DbSchedulerConsoleInfrastructureAutoConfiguration.class,
                        DbSchedulerConsoleAutoConfiguration.class))
                .withUserConfiguration(OwnSchedulerConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:noschedulerprops;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(DbSchedulerProperties.class);
                    assertThat(ctx).hasSingleBean(OverviewController.class);
                    assertThat(ctx.getBean(ExecutionRepository.class).tableName())
                            .isEqualTo("scheduled_tasks");
                });
    }

    @Test
    void propertiesBind() {
        runner.withPropertyValues(
                "db-scheduler-console.read-only=true",
                "db-scheduler-console.history.retention=7d",
                "db-scheduler-console.polling-interval=10s")
              .run(ctx -> {
                  var props = ctx.getBean(ConsoleProperties.class);
                  assertThat(props.isReadOnly()).isTrue();
                  assertThat(props.getHistory().getRetention()).isEqualTo(java.time.Duration.ofDays(7));
                  assertThat(props.getPollingInterval()).isEqualTo(java.time.Duration.ofSeconds(10));
              });
    }

    /** An app that builds its own Scheduler without the db-scheduler Boot starter. */
    @Configuration(proxyBeanMethods = false)
    static class OwnSchedulerConfig {
        @Bean
        Scheduler scheduler() {
            return mock(Scheduler.class);
        }
    }
}
