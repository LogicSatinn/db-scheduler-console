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
import io.github.logicsatinn.dbscheduler.console.data.FailedExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.ExecutionActions;
import io.github.logicsatinn.dbscheduler.console.service.ExecutionsService;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import com.github.kagkarlsson.scheduler.task.Task;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionOperations;

class ActionsControllerTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");
    static final OneTimeTask<Void> TASK = Tasks.oneTime("action-task").execute((i, c) -> {});

    SchedulerClient client;
    ExecutionRepository repo;
    JdbcTemplate jdbc;
    HistoryRepository history;
    ConsoleProperties props;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        DataSource ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        jdbc = new JdbcTemplate(ds);
        client = SchedulerClient.Builder.create(ds, TASK).build();
        repo = new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2);
        var failed = new FailedExecutionRepository(ds, "scheduled_tasks", Dialect.H2);
        history = new HistoryRepository(ds, Dialect.H2);
        props = new ConsoleProperties();
        props.setReadOnly(false);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var controller = new ActionsController(props, new TemplateRenderer(),
                new ExecutionActions(client, clock, failed, history,
                        Serializer.DEFAULT_JAVA_SERIALIZER, List.<Task<?>>of(TASK),
                        TransactionOperations.withoutTransaction()),
                new ExecutionsService(repo, failed, List.of(TASK)), clock);
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

    void failing(String task, String id) {
        jdbc.update("insert into scheduled_tasks (task_name, task_instance, execution_time, picked,"
                        + " consecutive_failures, version) values (?, ?, ?, false, 2, 1)",
                task, id, java.sql.Timestamp.from(NOW.plus(1, ChronoUnit.HOURS)));
    }

    @Test
    void retryRunsAFailedExecutionNow() throws Exception {
        failing("action-task", "a");
        var body = mvc.perform(post("/db-scheduler-console/actions/retry")
                        .param("task", "action-task").param("id", "a"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", "dsc-refresh"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("Retry succeeded for a");
        assertThat(repo.find("action-task", "a").orElseThrow().executionTime()).isEqualTo(NOW);
    }

    @Test
    void retryMatchingRetriesEveryExecutionTheFiltersSelect() throws Exception {
        for (int i = 0; i < 3; i++) failing("action-task", "f-" + i);
        failing("other-task", "o-1");
        client.scheduleIfNotExists(TASK.instance("f-fresh"), NOW.plus(1, ChronoUnit.HOURS));

        var body = mvc.perform(post("/db-scheduler-console/actions/retry-matching")
                        .param("state", "RETRYING").param("task", "action-task").param("q", "f-"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", "dsc-refresh"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Retry: 3 succeeded, 0 failed").doesNotContain("first");
        for (int i = 0; i < 3; i++)
            assertThat(repo.find("action-task", "f-" + i).orElseThrow().executionTime()).isEqualTo(NOW);
        assertThat(repo.find("other-task", "o-1").orElseThrow().executionTime()).isAfter(NOW);
        assertThat(repo.find("action-task", "f-fresh").orElseThrow().executionTime()).isAfter(NOW);
    }

    @Test
    void retryMatchingStopsAtTheLimitAndSaysSo() throws Exception {
        for (int i = 0; i <= ActionsController.MAX_RETRY_MATCHING; i++) failing("action-task", "f-%04d".formatted(i));

        var body = mvc.perform(post("/db-scheduler-console/actions/retry-matching").param("state", "RETRYING"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Retry: 1000 succeeded, 0 failed")
                .contains("only the first 1000 matching executions were retried");
        assertThat(repo.find("action-task", "f-1000").orElseThrow().executionTime()).isAfter(NOW);
    }

    @Test
    void retryMatchingNeedsAFailureStateFilter() throws Exception {
        failing("action-task", "a");
        for (String state : new String[] {"", "SCHEDULED", "RUNNING", "unknown"}) {
            mvc.perform(post("/db-scheduler-console/actions/retry-matching").param("state", state))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/db-scheduler-console/actions/retry-matching")
                        .param("state", "RETRYING").param("from", "not-a-date"))
                .andExpect(status().isBadRequest());
        assertThat(repo.find("action-task", "a").orElseThrow().executionTime()).isAfter(NOW);
    }

    @Test
    void rerunSchedulesAFailedRunAgain() throws Exception {
        history.insert(new HistoryEntry(0, "action-task", "gone", HistoryEntry.Outcome.FAILED,
                NOW.minusSeconds(30), NOW.minusSeconds(29), 1000, "java.lang.RuntimeException",
                "boom", "stack", "node-1", null, "java.lang.Void"));
        long id = history.forInstance("action-task", "gone", 1).get(0).id();

        var body = mvc.perform(post("/db-scheduler-console/actions/rerun").param("history", String.valueOf(id)))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", "dsc-refresh"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Re-run scheduled for gone");
        assertThat(repo.find("action-task", "gone").orElseThrow().executionTime()).isEqualTo(NOW);
        for (String bad : new String[] {"0", "-1", "abc"}) {
            mvc.perform(post("/db-scheduler-console/actions/rerun").param("history", bad))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void readOnlyBlocksRetryActionsWith403() throws Exception {
        props.setReadOnly(true);
        failing("action-task", "a");
        mvc.perform(post("/db-scheduler-console/actions/retry").param("task", "action-task").param("id", "a"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/db-scheduler-console/actions/retry-matching").param("state", "RETRYING"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/db-scheduler-console/actions/rerun").param("history", "1"))
                .andExpect(status().isForbidden());
        assertThat(repo.find("action-task", "a").orElseThrow().executionTime()).isAfter(NOW);
    }

}
