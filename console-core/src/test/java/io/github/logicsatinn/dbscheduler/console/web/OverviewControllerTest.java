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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OverviewControllerTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:30:00Z");

    MockMvc mvc;
    HistoryRepository history;

    @BeforeEach
    void setUp() {
        DataSource ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        history = new HistoryRepository(ds, Dialect.H2);
        var stats = new StatsService(new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2),
                history, Clock.fixed(NOW, ZoneOffset.UTC));
        var controller = new OverviewController(
                new PageCtxFactory(new ConsoleProperties()), new TemplateRenderer(), stats, history);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addPlaceholderValue("db-scheduler-console.base-path", "/db-scheduler-console")
                .build();
    }

    void seedHistory() {
        history.insert(new HistoryEntry(0, "email", "1", HistoryEntry.Outcome.SUCCEEDED,
                NOW.minusSeconds(300), NOW.minusSeconds(298), 2000, null, null, null, "n1"));
        history.insert(new HistoryEntry(0, "email", "2", HistoryEntry.Outcome.FAILED,
                NOW.minusSeconds(200), NOW.minusSeconds(199), 1000,
                "java.lang.RuntimeException", "smtp down", "stack", "n1"));
    }

    @Test
    void recentFailureLinkIsUrlEncoded() throws Exception {
        history.insert(new HistoryEntry(0, "email", "order+A&B C", HistoryEntry.Outcome.FAILED,
                NOW.minusSeconds(100), NOW.minusSeconds(99), 1000,
                "java.lang.RuntimeException", "smtp down", "stack", "n1"));

        var doc = Jsoup.parse(mvc.perform(get("/db-scheduler-console/overview"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(doc.select("#recent-failures td a").attr("href"))
                .isEqualTo("/db-scheduler-console/execution?task=email&id=order%2BA%26B%20C");
    }

    @Test
    void overviewShowsTilesChartAndRecentFailures() throws Exception {
        seedHistory();
        var body = mvc.perform(get("/db-scheduler-console/overview"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var doc = Jsoup.parse(body);

        assertThat(doc.select(".tile .label").eachText())
                .contains("Scheduled", "Due", "Running", "Failing", "Succeeded · 24h", "Failed · 24h");
        assertThat(doc.select("svg.chart-svg")).hasSize(1);
        assertThat(doc.select("svg rect.viz-good")).hasSize(1);
        assertThat(doc.select("svg rect.viz-critical")).hasSize(1);
        assertThat(doc.select(".legend").text()).contains("Succeeded").contains("Failed");
        assertThat(doc.select("svg title").eachText())
                .anyMatch(t -> t.contains("1 succeeded") && t.contains("1 failed"));
        assertThat(doc.select("#recent-failures td").eachText()).anyMatch(t -> t.contains("smtp down"));
        // polling wired
        assertThat(doc.select("[hx-get$=/fragments/overview-tiles][hx-trigger^=every]")).hasSize(1);
    }

    @Test
    void fragmentsReturnBareHtml() throws Exception {
        seedHistory();
        for (String frag : new String[] {"overview-tiles", "overview-chart", "overview-recent"}) {
            var body = mvc.perform(get("/db-scheduler-console/fragments/" + frag))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("<html").doesNotContain("<nav");
        }
    }

    @Test
    void chartVmGeometry() {
        var buckets = new java.util.ArrayList<StatsService.HourBucket>();
        Instant start = Instant.parse("2026-07-12T13:00:00Z");
        for (int i = 0; i < 24; i++) {
            buckets.add(new StatsService.HourBucket(start.plusSeconds(i * 3600L),
                    i == 23 ? 10 : 0, i == 23 ? 5 : 0));
        }
        var model = ChartVm.of(buckets);
        assertThat(model.bars()).hasSize(24);
        var last = model.bars().get(23);
        // succeeded sits on the baseline (y + height == 140)
        assertThat(last.ySucceeded() + last.heightSucceeded()).isEqualTo(140.0);
        // failed stacked above with a 2px surface gap
        assertThat(last.yFailed() + last.heightFailed()).isEqualTo(last.ySucceeded() - 2);
        assertThat(model.empty()).isFalse();

        var empty = ChartVm.of(java.util.List.of());
        assertThat(empty.empty()).isTrue();
    }
}
