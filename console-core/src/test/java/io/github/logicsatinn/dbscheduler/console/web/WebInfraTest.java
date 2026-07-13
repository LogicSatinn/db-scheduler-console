package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.StatsService;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WebInfraTest {

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        var ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        var props = new ConsoleProperties();
        var history = new HistoryRepository(ds, Dialect.H2);
        var stats = new StatsService(
                new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2), history, Clock.systemUTC());
        var overview = new OverviewController(
                new PageCtxFactory(props), new TemplateRenderer(), stats, history);
        mvc = MockMvcBuilders.standaloneSetup(overview, new StaticAssetsController())
                .addPlaceholderValue("db-scheduler-console.base-path", "/db-scheduler-console")
                .build();
    }

    @Test
    void overviewRendersLayout() throws Exception {
        var body = mvc.perform(get("/db-scheduler-console/overview"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andReturn().getResponse().getContentAsString();
        var doc = org.jsoup.Jsoup.parse(body);
        assertThat(doc.select("nav .brand").text()).isEqualTo("db-scheduler-console");
        assertThat(doc.select("nav a.active").text()).isEqualTo("Overview");
        assertThat(doc.select("script[src$=htmx.min.js]")).hasSize(1);
        assertThat(doc.select("link[href$=console.css]")).hasSize(1);
    }

    @Test
    void rootRedirectsToOverviewContent() throws Exception {
        mvc.perform(get("/db-scheduler-console")).andExpect(status().isOk());
    }

    @Test
    void servesStaticAssets() throws Exception {
        var css = mvc.perform(get("/db-scheduler-console/static/console.css"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(css.getContentType()).startsWith("text/css");
        assertThat(css.getContentAsString()).contains("--status-good");

        var js = mvc.perform(get("/db-scheduler-console/static/htmx.min.js"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        assertThat(js.getContentAsString()).contains("htmx");

        mvc.perform(get("/db-scheduler-console/static/nope.js"))
                .andExpect(status().isNotFound());
    }
}
