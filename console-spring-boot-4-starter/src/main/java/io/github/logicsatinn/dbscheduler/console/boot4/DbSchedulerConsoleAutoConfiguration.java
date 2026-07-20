package io.github.logicsatinn.dbscheduler.console.boot4;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.task.Task;
import io.github.logicsatinn.dbscheduler.console.ConsoleAvailability;
import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.FailedExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.ExecutionActions;
import io.github.logicsatinn.dbscheduler.console.service.ExecutionsService;
import io.github.logicsatinn.dbscheduler.console.service.RecurringTasksService;
import io.github.logicsatinn.dbscheduler.console.service.StatsService;
import io.github.logicsatinn.dbscheduler.console.service.TaskDataRenderer;
import io.github.logicsatinn.dbscheduler.console.web.ActionsController;
import io.github.logicsatinn.dbscheduler.console.web.ConsoleAvailabilityInterceptor;
import io.github.logicsatinn.dbscheduler.console.web.ExecutionsController;
import io.github.logicsatinn.dbscheduler.console.web.HistoryController;
import io.github.logicsatinn.dbscheduler.console.web.OverviewController;
import io.github.logicsatinn.dbscheduler.console.web.PageCtxFactory;
import io.github.logicsatinn.dbscheduler.console.web.RecurringController;
import io.github.logicsatinn.dbscheduler.console.web.StaticAssetsController;
import io.github.logicsatinn.dbscheduler.console.web.TemplateRenderer;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration(afterName = {
        "com.github.kagkarlsson.scheduler.boot.autoconfigure.DbSchedulerAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"})
@ConditionalOnClass(Scheduler.class)
@ConditionalOnBean({Scheduler.class, DataSource.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "db-scheduler-console", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ConsoleProperties.class)
public class DbSchedulerConsoleAutoConfiguration {

    private static final Clock CLOCK = Clock.systemUTC();

    @Bean
    ExecutionsService dbSchedulerConsoleExecutionsService(ExecutionRepository executions,
            FailedExecutionRepository failedExecutions) {
        return new ExecutionsService(executions, failedExecutions);
    }

    @Bean
    StatsService dbSchedulerConsoleStatsService(ExecutionRepository executions,
            FailedExecutionRepository failedExecutions,
            HistoryRepository history) {
        return new StatsService(executions, failedExecutions, history, CLOCK);
    }

    @Bean
    ExecutionActions dbSchedulerConsoleExecutionActions(Scheduler scheduler,
            FailedExecutionRepository failedExecutions, List<Task<?>> tasks,
            ObjectProvider<DbSchedulerCustomizer> customizer) {
        return new ExecutionActions(scheduler, CLOCK, failedExecutions,
                effectiveSerializer(customizer), tasks);
    }

    @Bean
    RecurringTasksService dbSchedulerConsoleRecurringTasksService(List<Task<?>> tasks,
            ExecutionRepository executions, HistoryRepository history) {
        return new RecurringTasksService(tasks, executions, history);
    }

    @Bean
    TaskDataRenderer dbSchedulerConsoleTaskDataRenderer(ConsoleProperties props,
            ObjectProvider<DbSchedulerCustomizer> customizer) {
        return new TaskDataRenderer(effectiveSerializer(customizer),
                props.getTaskData().isVisible());
    }


    @Bean
    TemplateRenderer dbSchedulerConsoleTemplateRenderer() {
        return new TemplateRenderer();
    }

    @Bean
    PageCtxFactory dbSchedulerConsolePageCtxFactory(ConsoleProperties props) {
        return new PageCtxFactory(props);
    }

    @Bean
    OverviewController dbSchedulerConsoleOverviewController(PageCtxFactory ctx,
            TemplateRenderer templates, StatsService stats, HistoryRepository history) {
        return new OverviewController(ctx, templates, stats, history);
    }

    @Bean
    ExecutionsController dbSchedulerConsoleExecutionsController(PageCtxFactory ctx,
            TemplateRenderer templates, ExecutionsService executions, HistoryRepository history,
            TaskDataRenderer taskData, StatsService stats) {
        return new ExecutionsController(ctx, templates, executions, history, taskData, stats, CLOCK);
    }

    @Bean
    RecurringController dbSchedulerConsoleRecurringController(PageCtxFactory ctx,
            TemplateRenderer templates, RecurringTasksService service) {
        return new RecurringController(ctx, templates, service);
    }

    @Bean
    HistoryController dbSchedulerConsoleHistoryController(PageCtxFactory ctx,
            TemplateRenderer templates, HistoryRepository history, ConsoleAvailability availability,
            TaskDataRenderer taskData) {
        return new HistoryController(ctx, templates, history,
                availability.dialectOrFallback(), taskData);
    }

    @Bean
    ActionsController dbSchedulerConsoleActionsController(ConsoleProperties props,
            TemplateRenderer templates, ExecutionActions actions) {
        return new ActionsController(props, templates, actions);
    }

    @Bean
    StaticAssetsController dbSchedulerConsoleStaticAssetsController() {
        return new StaticAssetsController();
    }

    @Bean
    WebMvcConfigurer dbSchedulerConsoleWebMvcConfigurer(ConsoleAvailability availability,
            ConsoleProperties props) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new ConsoleAvailabilityInterceptor(availability))
                        .addPathPatterns(props.getBasePath() + "/**");
            }
        };
    }

    private static Serializer effectiveSerializer(
            ObjectProvider<DbSchedulerCustomizer> customizer) {
        return customizer.stream()
                .flatMap(candidate -> candidate.serializer().stream())
                .findFirst()
                .orElse(Serializer.DEFAULT_JAVA_SERIALIZER);
    }
}
