package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.StatsService;
import io.github.logicsatinn.dbscheduler.console.service.TaskDataRenderer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
        history = new HistoryRepository(ds, Dialect.H2);
        var controller = new ExecutionsController(
                new PageCtxFactory(new ConsoleProperties()), new TemplateRenderer(),
                repo, history, new TaskDataRenderer(null, true),
                new StatsService(repo, history, Clock.fixed(NOW, ZoneOffset.UTC)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(controller)
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
        // filter form present, URL-synced
        assertThat(doc.select("form[hx-push-url=true]")).hasSize(1);
        // checkbox refs for bulk selection
        assertThat(doc.select("input[name=ref]").attr("value")).isEqualTo("email::running");
    }

    @Test
    void searchPaginationAndSortLinks() throws Exception {
        for (int i = 0; i < 30; i++) {
            insert("bulk", "i-%02d".formatted(i), NOW.plus(i, ChronoUnit.MINUTES), false, 0, null);
        }
        var doc = Jsoup.parse(mvc.perform(get("/db-scheduler-console/executions?q=i-2&size=10"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(doc.select("#executions-region tbody tr")).hasSize(10); // i-20 … i-29
        assertThat(doc.select(".pagination").text()).contains("Page 1 of 1");

        var page2 = Jsoup.parse(mvc.perform(get("/db-scheduler-console/executions?size=10&page=1"))
                .andReturn().getResponse().getContentAsString());
        assertThat(page2.select(".pagination").text()).contains("Page 2 of 3");
        // sort header link toggles direction
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
        assertThat(doc.select("pre.data").text()).contains("orderId");
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
    void missingExecutionIs404() throws Exception {
        mvc.perform(get("/db-scheduler-console/execution?task=email&id=missing"))
                .andExpect(status().isNotFound());
    }
}
