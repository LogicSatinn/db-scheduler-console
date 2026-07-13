package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.StatsService;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CsrfRenderingTest {

    @Test
    void csrfTokenBecomesHxHeaders() throws Exception {
        var ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        var history = new HistoryRepository(ds, Dialect.H2);
        var controller = new OverviewController(
                new PageCtxFactory(new ConsoleProperties()), new TemplateRenderer(),
                new StatsService(new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2),
                        history, Clock.systemUTC()),
                history);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .addPlaceholderValue("db-scheduler-console.base-path", "/db-scheduler-console")
                .build();

        var body = mvc.perform(get("/db-scheduler-console/overview")
                        .requestAttr(CsrfToken.class.getName(),
                                new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token-123")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("hx-headers").contains("X-CSRF-TOKEN").contains("token-123");

        var without = mvc.perform(get("/db-scheduler-console/overview"))
                .andReturn().getResponse().getContentAsString();
        assertThat(without).doesNotContain("hx-headers");
    }
}
