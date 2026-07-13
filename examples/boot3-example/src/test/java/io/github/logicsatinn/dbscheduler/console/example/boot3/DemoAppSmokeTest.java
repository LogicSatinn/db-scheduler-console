package io.github.logicsatinn.dbscheduler.console.example.boot3;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoAppSmokeTest {

    @Value("${local.server.port}")
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    String get(String path) throws Exception {
        var response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
        return response.body();
    }

    @Test
    void allPagesServe() throws Exception {
        assertThat(get("/db-scheduler-console/overview")).contains("db-scheduler-console");
        assertThat(get("/db-scheduler-console/executions")).contains("Executions");
        assertThat(get("/db-scheduler-console/recurring")).contains("demo-steady");
        assertThat(get("/db-scheduler-console/history")).contains("history");
        assertThat(get("/db-scheduler-console/static/htmx.min.js")).contains("htmx");
    }
}
