package io.github.logicsatinn.dbscheduler.console.example.boot3;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.HistoryPurgeTask;
import java.net.URI;
import java.net.CookieManager;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoAppSmokeTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    Scheduler scheduler;

    @Autowired
    OneTimeTask<Void> emailTask;

    @Autowired
    HistoryRepository history;

    static final String AUTH = "Basic " + Base64.getEncoder().encodeToString(
            "admin:demo-password".getBytes(StandardCharsets.UTF_8));
    static final Pattern CSRF = Pattern.compile("\\{\\\"([^\\\"]+)\\\":\\\"([^\\\"]+)\\\"}");

    final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

    String get(String path) throws Exception {
        var response = http.send(HttpRequest.newBuilder(uri(path))
                        .header("Authorization", AUTH).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
        return response.body();
    }

    HttpResponse<String> post(String path, String csrfHeader, String csrfToken) throws Exception {
        var request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", AUTH)
                .header("Content-Type", "application/x-www-form-urlencoded");
        if (csrfHeader != null) {
            request.header(csrfHeader, csrfToken);
        }
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(
                "task=missing&id=missing")).build(), HttpResponse.BodyHandlers.ofString());
    }

    URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @Test
    void allPagesServe() throws Exception {
        var anonymous = http.send(HttpRequest.newBuilder(uri("/db-scheduler-console/overview"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(get("/db-scheduler-console/overview")).contains("db-scheduler-console");
        assertThat(get("/db-scheduler-console/executions")).contains("Executions");
        assertThat(get("/db-scheduler-console/recurring")).contains("demo-steady");
        assertThat(get("/db-scheduler-console/history")).contains("history");
        assertThat(get("/db-scheduler-console/static/htmx.min.js")).contains("htmx");
    }

    @Test
    void mutationsRequireCsrfInAdditionToBasicAuthentication() throws Exception {
        assertThat(post("/db-scheduler-console/actions/run-now", null, null).statusCode())
                .isEqualTo(403);

        String headers = Jsoup.parse(get("/db-scheduler-console/executions"))
                .selectFirst("body").attr("hx-headers");
        var matcher = CSRF.matcher(headers);
        assertThat(matcher.matches()).isTrue();
        assertThat(post("/db-scheduler-console/actions/run-now",
                matcher.group(1), matcher.group(2)).statusCode()).isEqualTo(200);
    }

    @Test
    void historyListenerAndPurgeTaskAreInstalledBeforeSchedulerBuilds() throws Exception {
        String instanceId = "history-smoke-" + System.nanoTime();
        scheduler.scheduleIfNotExists(emailTask.instance(instanceId), java.time.Instant.now());
        scheduler.triggerCheckForDueExecutions();

        for (int attempt = 0; attempt < 50
                && history.forInstance(emailTask.getName(), instanceId, 1).isEmpty(); attempt++) {
            Thread.sleep(100);
        }

        assertThat(history.forInstance(emailTask.getName(), instanceId, 1)).hasSize(1);
        assertThat(scheduler.getScheduledExecution(TaskInstanceId.of(
                HistoryPurgeTask.NAME, RecurringTask.INSTANCE))).isPresent();
    }
}
