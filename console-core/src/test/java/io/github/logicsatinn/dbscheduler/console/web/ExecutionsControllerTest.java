package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.FailedExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.StatsService;
import io.github.logicsatinn.dbscheduler.console.service.ExecutionsService;
import io.github.logicsatinn.dbscheduler.console.service.TaskDataRenderer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.sql.DataSource;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ExecutionsControllerTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    MockMvc mvc;
    JdbcTemplate jdbc;
    HistoryRepository history;

    @BeforeEach
    void setUp() {
        DataSource ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        jdbc = new JdbcTemplate(ds);
        var repo = new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2);
        var failed = new FailedExecutionRepository(ds, "scheduled_tasks", Dialect.H2);
        history = new HistoryRepository(ds, Dialect.H2);
        var props = new ConsoleProperties();
        props.setReadOnly(false);
        var controller = new ExecutionsController(
                new PageCtxFactory(props), new TemplateRenderer(),
                new ExecutionsService(repo, failed, List.of(
                        Tasks.recurring("cleanup", FixedDelay.ofHours(1)).execute((i, c) -> {}),
                        Tasks.oneTime("email").execute((i, c) -> {}))), history, new TaskDataRenderer(null, true),
                new StatsService(repo, failed, history, Clock.fixed(NOW, ZoneOffset.UTC)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ConsoleErrors())
                .addPlaceholderValue("db-scheduler-console.base-path", "/db-scheduler-console")
                .build();
    }

    void insert(String task, String id, Instant at, boolean picked, int failures, byte[] data) {
        jdbc.update("insert into scheduled_tasks"
                + " (task_name, task_instance, task_data, execution_time, picked, picked_by, consecutive_failures, version)"
                + " values (?, ?, ?, ?, ?, ?, ?, 1)",
                task, id, data, Timestamp.from(at), picked, picked ? "node-1" : null, failures);
    }

    @Test
    void listsAndFiltersByState() throws Exception {
        insert("email", "future", NOW.plus(1, ChronoUnit.HOURS), false, 0, null);
        insert("email", "running", NOW, true, 0, null);

        var doc = Jsoup.parse(mvc.perform(get("/db-scheduler-console/executions?state=RUNNING"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(doc.select("#executions-region tbody tr")).hasSize(1);
        assertThat(doc.select("#executions-region tbody").text()).contains("running");
        assertThat(doc.select("form[hx-push-url=true]")).hasSize(1);
        assertThat(doc.select("input[name=ref]").attr("value")).isEqualTo("email::running");
    }

    @Test
    void filtersByTaskTypeAndKeepsTheTypeInLinks() throws Exception {
        insert("cleanup", "recurring", NOW.plus(1, ChronoUnit.HOURS), false, 0, null);
        insert("email", "order-1", NOW.plus(2, ChronoUnit.HOURS), false, 0, null);
        insert("unregistered", "x", NOW.plus(3, ChronoUnit.HOURS), false, 0, null);

        var recurring = Jsoup.parse(mvc.perform(get("/db-scheduler-console/executions?type=RECURRING"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(recurring.select("input[name=ref]").eachAttr("value")).containsExactly("cleanup::recurring");
        assertThat(recurring.select("select[name=type] option[selected]").val()).isEqualTo("RECURRING");
        assertThat(recurring.select("th a").eachAttr("href")).allMatch(href -> href.contains("type=RECURRING"));

        var oneTime = Jsoup.parse(mvc.perform(get("/db-scheduler-console/executions?type=ONE_TIME"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(oneTime.select("input[name=ref]").eachAttr("value")).containsExactly("email::order-1");

        var all = Jsoup.parse(mvc.perform(get("/db-scheduler-console/executions"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(all.select("input[name=ref]")).hasSize(3);
    }

    @Test
    void searchPaginationAndSortLinks() throws Exception {
        for (int i = 0; i < 30; i++) {
            insert("bulk", "i-%02d".formatted(i), NOW.plus(i, ChronoUnit.MINUTES), false, 0, null);
        }
        var doc = Jsoup.parse(mvc.perform(get("/db-scheduler-console/executions?q=i-2&size=10"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(doc.select("#executions-region tbody tr")).hasSize(10);
        assertThat(doc.select(".pagination").text()).contains("10 entries");

        var first = Jsoup.parse(mvc.perform(get("/db-scheduler-console/executions?size=10"))
                .andReturn().getResponse().getContentAsString());
        String next = first.select(".pagination a").last().attr("href");
        var page2 = Jsoup.parse(mvc.perform(get(URI.create(next)))
                .andReturn().getResponse().getContentAsString());
        assertThat(page2.select(".pagination").text()).contains("10 entries", "Previous", "Next");
        assertThat(page2.select("th a[href*='sort=EXECUTION_TIME']").attr("href")).contains("dir=desc");
    }

    @Test
    void detailShowsDataAndHistory() throws Exception {
        insert("email", "order-1", NOW.plus(1, ChronoUnit.HOURS), false, 0,
                "{\"orderId\":42}".getBytes(StandardCharsets.UTF_8));
        history.insert(new HistoryEntry(0, "email", "order-1", HistoryEntry.Outcome.FAILED,
                NOW.minusSeconds(60), NOW.minusSeconds(58), 2000,
                "java.lang.RuntimeException", "boom", "the-stack-trace", "node-1"));

        var doc = Jsoup.parse(mvc.perform(
                        get("/db-scheduler-console/execution?task=email&id=order-1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(doc.text()).doesNotContain("orderId");
        String payloadUrl = doc.select("details[data-payload-url]").attr("data-payload-url");
        assertThat(mvc.perform(get(URI.create(payloadUrl))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).contains("orderId");
        assertThat(doc.select("details.stack pre").text()).contains("the-stack-trace");
        assertThat(doc.text()).contains("SCHEDULED");
    }

    @Test
    void detailLinkRoundTripsForIdentifiersNeedingEncoding() throws Exception {
        String task = "email&send";
        String id = "order+A&B C";
        insert(task, id, NOW.plus(1, ChronoUnit.HOURS), false, 0, null);

        var list = Jsoup.parse(mvc.perform(get("/db-scheduler-console/executions"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String href = list.select("#executions-region tbody td a").attr("href");
        assertThat(href).isEqualTo(
                "/db-scheduler-console/execution?task=email%26send&id=order%2BA%26B%20C");

        var detail = Jsoup.parse(mvc.perform(get(URI.create(href)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(detail.select("h1").text()).contains(task).contains(id);
    }

    @Test
    void actionFlashesAreNotFilteredByRefreshRegions() throws Exception {
        insert("email", "order-1", NOW.plus(1, ChronoUnit.HOURS), false, 0, null);

        HtmxAssertions.actionFormsDoNotInheritRegionSelect(Jsoup.parse(
                mvc.perform(get("/db-scheduler-console/executions"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()));
        HtmxAssertions.actionFormsDoNotInheritRegionSelect(Jsoup.parse(
                mvc.perform(get("/db-scheduler-console/execution?task=email&id=order-1"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()));
    }

    @Test
    void tablePollsWithItsFiltersAndPausesWhileRowsAreSelected() throws Exception {
        insert("email", "order-1", NOW.plus(1, ChronoUnit.HOURS), false, 0, null);

        var region = Jsoup.parse(mvc.perform(get(
                        "/db-scheduler-console/executions?state=SCHEDULED&task=email&sort=TASK_NAME&dir=desc"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .getElementById("executions-region");

        assertThat(region.attr("hx-get"))
                .as("relative, so it also works behind an HTTPS proxy")
                .startsWith("/db-scheduler-console/executions?")
                .contains("state=SCHEDULED", "task=email", "sort=TASK_NAME", "dir=desc");
        assertThat(region.attr("hx-trigger"))
                .startsWith("every 5s")
                .endsWith(", dsc-refresh from:body");
    }

    @Test
    void liveDetailPollsAndPausesWhileReschedulingOrReadingAStacktrace() throws Exception {
        insert("email", "order-1", NOW.plus(1, ChronoUnit.HOURS), false, 0, null);

        var doc = Jsoup.parse(mvc.perform(get("/db-scheduler-console/execution?task=email&id=order-1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var region = doc.getElementById("detail-live");

        assertThat(region).isNotNull();
        assertThat(region.attr("hx-select")).isEqualTo("#detail-live");
        assertThat(region.attr("hx-swap")).isEqualTo("outerHTML");
        assertThat(region.hasAttr("hx-target"))
                .as("htmx fires before-swap on the target, so the region must be its own target")
                .isFalse();
        assertThat(region.attr("hx-trigger"))
                .startsWith("every 5s")
                .endsWith(", dsc-refresh from:body");
        assertThat(region.attr("hx-on::before:swap"))
                .as("handlers are external for CSP")
                .isEmpty();
    }

    @Test
    void finishedExecutionShowsItsOutcomeAndHistoryAndStopsPolling() throws Exception {
        history.insert(new HistoryEntry(0, "email", "order-9", HistoryEntry.Outcome.SUCCEEDED,
                NOW.minusSeconds(30), NOW.minusSeconds(29), 1000, null, null, null, "node-1"));

        var doc = Jsoup.parse(mvc.perform(get("/db-scheduler-console/execution?task=email&id=order-9"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(doc.select("h1").text()).contains("email").contains("order-9");
        assertThat(doc.select("#detail-live").text()).contains("Finished").contains("SUCCEEDED");
        assertThat(doc.select("#detail-live table.data tbody tr")).hasSize(1);
        assertThat(doc.select("[hx-trigger]")).isEmpty();
    }

    @Test
    void missingExecutionIs404() throws Exception {
        var html = mvc.perform(get("/db-scheduler-console/execution?task=email&id=missing"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(Jsoup.parse(html).select("#detail-live").text())
                .as("rendered as the region a polling detail page swaps in")
                .contains("not found");
    }
    @Test
    void malformedFiltersAndCursorReturnControlledBadRequests() throws Exception {
        for (String query : java.util.List.of("cursor=malformed", "size=2147483647", "size=-1", "sort=unknown",
                "from=not-a-date", "dir=unknown", "state=unknown", "type=unknown")) {
            mvc.perform(get("/db-scheduler-console/executions?" + query))
                    .andExpect(status().isBadRequest());
        }
    }

}
