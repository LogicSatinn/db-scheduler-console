package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.time.Instant;
import javax.sql.DataSource;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HistoryControllerTest {

    static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    MockMvc mvcFor(boolean withHistoryTable) {
        var builder = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql");
        if (withHistoryTable) {
            builder.addScript("db-scheduler-console/migrations/h2.sql");
        }
        DataSource ds = builder.build();
        var repo = new HistoryRepository(ds, Dialect.H2);
        if (withHistoryTable) {
            repo.insert(new HistoryEntry(0, "email", "1", HistoryEntry.Outcome.FAILED,
                    NOW.minusSeconds(60), NOW.minusSeconds(58), 2000,
                    "java.lang.RuntimeException", "smtp down", "the-stack", "n1"));
            repo.insert(new HistoryEntry(0, "report", "2", HistoryEntry.Outcome.SUCCEEDED,
                    NOW.minusSeconds(30), NOW.minusSeconds(29), 1000, null, null, null, "n1"));
        }
        var controller = new HistoryController(
                new PageCtxFactory(new ConsoleProperties()), new TemplateRenderer(), repo, Dialect.H2);
        return MockMvcBuilders.standaloneSetup(controller)
                .addPlaceholderValue("db-scheduler-console.base-path", "/db-scheduler-console")
                .build();
    }

    @Test
    void listsAndFilters() throws Exception {
        var mvc = mvcFor(true);
        var doc = Jsoup.parse(mvc.perform(get("/db-scheduler-console/history"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(doc.select("#history-region tbody tr")).hasSize(2);

        var failed = Jsoup.parse(mvc.perform(get("/db-scheduler-console/history?outcome=FAILED"))
                .andReturn().getResponse().getContentAsString());
        assertThat(failed.select("#history-region tbody tr")).hasSize(1);
        assertThat(failed.select("details.stack pre").text()).contains("the-stack");

        var text = Jsoup.parse(mvc.perform(get("/db-scheduler-console/history?q=smtp"))
                .andReturn().getResponse().getContentAsString());
        assertThat(text.select("#history-region tbody tr")).hasSize(1);
    }

    @Test
    void missingTableShowsSetupPage() throws Exception {
        var mvc = mvcFor(false);
        var doc = Jsoup.parse(mvc.perform(get("/db-scheduler-console/history"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(doc.text()).contains("history table has not been created");
        assertThat(doc.select("pre").text()).contains("create table dsc_execution_history");
        assertThat(doc.text()).contains("H2");
    }
}
