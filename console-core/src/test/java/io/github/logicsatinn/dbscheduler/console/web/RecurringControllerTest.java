package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.RecurringTasksService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RecurringControllerTest {

    DataSource ds;
    RecurringTasksService service;

    @BeforeEach
    void setUp() {
        ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        List<Task<?>> known = List.of(
                Tasks.recurring("cleanup", FixedDelay.ofHours(1)).execute((i, c) -> {}),
                Tasks.recurring("purge&sweep old", FixedDelay.ofHours(1)).execute((i, c) -> {}),
                Tasks.oneTime("email").execute((i, c) -> {}));
        service = new RecurringTasksService(known,
                new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2),
                new HistoryRepository(ds, Dialect.H2));
    }

    @Test
    void listsKnownTasks() throws Exception {
        var doc = page(writable());
        assertThat(doc.select("table.data tbody tr")).hasSize(3);
        assertThat(doc.select("tbody").text()).contains("cleanup").contains("Recurring");
    }

    @Test
    void historyLinkIsUrlEncoded() throws Exception {
        var doc = page(writable());
        assertThat(doc.select("tbody a").eachAttr("href"))
                .contains("/db-scheduler-console/history?task=purge%26sweep%20old");
    }

    @Test
    void waitingRecurringTaskOffersRunNowForItsRecurringInstance() throws Exception {
        schedule("cleanup", false);

        Element form = row(page(writable()), "cleanup").selectFirst("form");

        assertThat(form).isNotNull();
        assertThat(form.attr("hx-post")).isEqualTo("/db-scheduler-console/actions/run-now");
        assertThat(form.attr("hx-target")).isEqualTo("#flash");
        assertThat(form.select("input[name=task]").val()).isEqualTo("cleanup");
        assertThat(form.select("input[name=id]").val()).isEqualTo(RecurringTask.INSTANCE);
        assertThat(form.select("button").text()).isEqualTo("Run now");
    }

    @Test
    void noRunNowWhileRunningWithoutALiveRowOrForOneTimeDefinitions() throws Exception {
        schedule("purge&sweep old", true);

        var doc = page(writable());

        assertThat(row(doc, "purge&sweep old").select("form")).as("running").isEmpty();
        assertThat(row(doc, "cleanup").select("form")).as("no live row").isEmpty();
        assertThat(row(doc, "email").select("form")).as("one-time definition").isEmpty();
    }

    @Test
    void runNowFlashIsNotFilteredByTheRefreshRegion() throws Exception {
        schedule("cleanup", false);

        HtmxAssertions.actionFormsDoNotInheritRegionSelect(page(writable()));
    }

    @Test
    void readOnlyConsoleHidesRunNow() throws Exception {
        schedule("cleanup", false);
        var props = new ConsoleProperties();
        props.setReadOnly(true);

        assertThat(row(page(props), "cleanup").select("form")).isEmpty();
    }

    @Test
    void tableRefreshesOnThePollingIntervalAndAfterAnAction() throws Exception {
        var props = new ConsoleProperties();
        props.setPollingInterval(Duration.ofSeconds(1));

        Element region = page(props).getElementById("recurring-region");

        assertThat(region).isNotNull();
        assertThat(region.attr("hx-get")).isEqualTo("/db-scheduler-console/recurring");
        assertThat(region.attr("hx-trigger")).isEqualTo("every 1s, dsc-refresh from:body");
        assertThat(region.attr("hx-select")).isEqualTo("#recurring-region");
        assertThat(region.select("table.data")).hasSize(1);
    }

    private static ConsoleProperties writable() {
        var props = new ConsoleProperties();
        props.setReadOnly(false);
        return props;
    }

    private void schedule(String task, boolean picked) {
        new JdbcTemplate(ds).update("insert into scheduled_tasks (task_name, task_instance,"
                        + " execution_time, picked, version) values (?, ?, ?, ?, 1)",
                task, RecurringTask.INSTANCE, Timestamp.from(Instant.now().plusSeconds(3600)), picked);
    }

    private Document page(ConsoleProperties props) throws Exception {
        var controller = new RecurringController(
                new PageCtxFactory(props), new TemplateRenderer(), service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .addPlaceholderValue("db-scheduler-console.base-path", "/db-scheduler-console")
                .build();
        return Jsoup.parse(mvc.perform(get("/db-scheduler-console/recurring"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static Element row(Document doc, String task) {
        return doc.select("table.data tbody tr").stream()
                .filter(tr -> tr.child(0).text().equals(task))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + task));
    }
}
