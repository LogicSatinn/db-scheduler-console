package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRepository;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.RecurringTasksService;
import java.util.List;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RecurringControllerTest {

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        var ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("db-scheduler-schema/h2.sql")
                .addScript("db-scheduler-console/migrations/h2.sql")
                .build();
        List<Task<?>> known = List.of(
                Tasks.recurring("cleanup", FixedDelay.ofHours(1)).execute((i, c) -> {}),
                Tasks.recurring("purge&sweep old", FixedDelay.ofHours(1)).execute((i, c) -> {}));
        var service = new RecurringTasksService(known,
                new ExecutionRepository(ds, "scheduled_tasks", Dialect.H2),
                new HistoryRepository(ds, Dialect.H2));
        var controller = new RecurringController(
                new PageCtxFactory(new ConsoleProperties()), new TemplateRenderer(), service);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addPlaceholderValue("db-scheduler-console.base-path", "/db-scheduler-console")
                .build();
    }

    @Test
    void listsKnownTasks() throws Exception {
        var doc = Jsoup.parse(mvc.perform(get("/db-scheduler-console/recurring"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(doc.select("table.data tbody tr")).hasSize(2);
        assertThat(doc.select("tbody").text()).contains("cleanup").contains("Recurring");
    }

    @Test
    void historyLinkIsUrlEncoded() throws Exception {
        var doc = Jsoup.parse(mvc.perform(get("/db-scheduler-console/recurring"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(doc.select("tbody a").eachAttr("href"))
                .contains("/db-scheduler-console/history?task=purge%26sweep%20old");
    }
}
