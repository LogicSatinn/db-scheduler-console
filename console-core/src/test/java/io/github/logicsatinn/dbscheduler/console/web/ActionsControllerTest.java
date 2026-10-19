package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.service.ExecutionActions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ActionsControllerTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");
    static final OneTimeTask<Void> TASK = Tasks.oneTime("action-task").execute((i, c) -> {});

    SchedulerClient client;
    ExecutionRepository repo;
    ConsoleProperties props;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        DataSource ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .build();
        client = SchedulerClient.Builder.create(ds, TASK).build();
        repo = new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2);
        props = new ConsoleProperties();
        props.setReadOnly(false);
        var controller = new ActionsController(props, new TemplateRenderer(),
                new ExecutionActions(client, Clock.fixed(NOW, ZoneOffset.UTC)));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ConsoleErrors())
                .addInterceptors(new ConsoleRequestInterceptor(props))
                .defaultRequest(post("/").header("HX-Request", "true"))
                .addPlaceholderValue("db-scheduler-console.base-path", "/db-scheduler-console")
                .build();
    }

    @Test
    void runNowActsAndSignalsRefresh() throws Exception {
        client.scheduleIfNotExists(TASK.instance("a"), NOW.plus(1, ChronoUnit.HOURS));
        var body = mvc.perform(post("/db-scheduler-console/actions/run-now")
                        .param("task", "action-task").param("id", "a"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", "dsc-refresh"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("flash").contains("succeeded");
        assertThat(repo.find("action-task", "a").orElseThrow().executionTime()).isEqualTo(NOW);
    }

    @Test
    void rescheduleParsesDatetimeLocal() throws Exception {
        client.scheduleIfNotExists(TASK.instance("a"), NOW.plus(1, ChronoUnit.HOURS));
        mvc.perform(post("/db-scheduler-console/actions/reschedule")
                        .param("task", "action-task").param("id", "a")
                        .param("when", "2026-07-14T09:30"))
                .andExpect(status().isOk());
        var newTime = repo.find("action-task", "a").orElseThrow().executionTime();
        assertThat(newTime).isEqualTo(java.time.LocalDateTime.parse("2026-07-14T09:30")
                .atZone(java.time.ZoneId.systemDefault()).toInstant());
    }

    @Test
    void deleteRemovesRow() throws Exception {
        client.scheduleIfNotExists(TASK.instance("a"), NOW.plus(1, ChronoUnit.HOURS));
        mvc.perform(post("/db-scheduler-console/actions/delete")
                        .param("task", "action-task").param("id", "a"))
                .andExpect(status().isOk());
        assertThat(repo.find("action-task", "a")).isEmpty();
    }

    @Test
    void bulkAggregatesAndReportsMixedResults() throws Exception {
        client.scheduleIfNotExists(TASK.instance("a"), NOW.plus(1, ChronoUnit.HOURS));
        var body = mvc.perform(post("/db-scheduler-console/actions/bulk")
                        .param("kind", "RUN_NOW")
                        .param("ref", "action-task::a").param("ref", "action-task::missing"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("1 succeeded").contains("1 failed").contains("error");
    }

    @Test
    void readOnlyBlocksEverythingWith403() throws Exception {
        props.setReadOnly(true);
        client.scheduleIfNotExists(TASK.instance("a"), NOW.plus(1, ChronoUnit.HOURS));
        var body = mvc.perform(post("/db-scheduler-console/actions/run-now")
                        .param("task", "action-task").param("id", "a"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("read-only");
        assertThat(repo.find("action-task", "a").orElseThrow().executionTime())
                .isEqualTo(NOW.plus(1, ChronoUnit.HOURS));
    }
    @Test
    void rejectsMalformedAndOversizedBatchesBeforeAnyMutation() throws Exception {
        client.scheduleIfNotExists(TASK.instance("a"), NOW.plusSeconds(3600));
        mvc.perform(post("/db-scheduler-console/actions/bulk").param("kind", "DELETE")
                .param("ref", "action-task::a", "malformed")).andExpect(status().isBadRequest());
        mvc.perform(post("/db-scheduler-console/actions/bulk").param("kind", "BAD")
                .param("ref", "action-task::a")).andExpect(status().isBadRequest());
        mvc.perform(post("/db-scheduler-console/actions/bulk").param("kind", "DELETE")
                .param("ref", java.util.Collections.nCopies(201, "action-task::a").toArray(String[]::new)))
                .andExpect(status().isBadRequest());
        assertThat(repo.find("action-task", "a")).isPresent();
    }
    @Test
    void rejectsCrossOriginAndMissingCustomHeader() throws Exception {
        client.scheduleIfNotExists(TASK.instance("a"), NOW.plusSeconds(3600));
        for (var request : java.util.List.of(
                post("/db-scheduler-console/actions/delete").header("Origin", "https://attacker.example"),
                post("/db-scheduler-console/actions/delete").header("Sec-Fetch-Site", "cross-site"),
                post("/db-scheduler-console/actions/delete").header("HX-Request", "false"))) {
            mvc.perform(request.param("task", "action-task").param("id", "a"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        assertThat(repo.find("action-task", "a")).isPresent();
    }
    @Test
    void acceptsExactlyTwoHundredReferencesAndDeduplicates() throws Exception {
        client.scheduleIfNotExists(TASK.instance("a"), NOW.plusSeconds(3600));
        String body = mvc.perform(post("/db-scheduler-console/actions/bulk").param("kind", "RUN_NOW")
                .param("ref", java.util.Collections.nCopies(200, "action-task::a").toArray(String[]::new)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("1 succeeded");
    }

}
