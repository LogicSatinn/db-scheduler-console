package io.github.logicsatinn.dbscheduler.console.example.boot4;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DemoTasks {

    @Bean
    Task<Void> steadyTask() {
        return Tasks.recurring("demo-steady", FixedDelay.ofSeconds(20)).execute((inst, ctx) -> {});
    }

    @Bean
    Task<Void> slowTask() {
        return Tasks.recurring("demo-slow", FixedDelay.ofMinutes(1)).execute((inst, ctx) -> {
            try {
                Thread.sleep(8000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Bean
    Task<Void> flakyTask() {
        return Tasks.recurring("demo-flaky", FixedDelay.ofSeconds(45)).execute((inst, ctx) -> {
            if (ThreadLocalRandom.current().nextBoolean()) {
                throw new IllegalStateException("Flaky demo failure — this is intentional");
            }
        });
    }

    @Bean
    OneTimeTask<Void> emailTask() {
        return Tasks.oneTime("demo-email").execute((inst, ctx) -> {});
    }

    @Bean
    CommandLineRunner seedDemoData(Scheduler scheduler, OneTimeTask<Void> emailTask) {
        return args -> {
            for (int i = 1; i <= 5; i++) {
                scheduler.scheduleIfNotExists(
                        emailTask.instance("order-" + i), Instant.now().plusSeconds(3600L * i));
            }
        };
    }
}
