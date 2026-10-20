package io.github.logicsatinn.dbscheduler.console.web;

import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.RetryLookup;
import io.github.logicsatinn.dbscheduler.console.service.StatsService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("${db-scheduler-console.base-path:/db-scheduler-console}")
public class OverviewController {

    private final PageCtxFactory ctxFactory;
    private final TemplateRenderer templates;
    private final StatsService stats;
    private final HistoryRepository history;
    private final RetryLookup retries;

    public OverviewController(PageCtxFactory ctxFactory, TemplateRenderer templates,
            StatsService stats, HistoryRepository history) {
        this(ctxFactory, templates, stats, history, null);
    }

    public OverviewController(PageCtxFactory ctxFactory, TemplateRenderer templates,
            StatsService stats, HistoryRepository history, RetryLookup retries) {
        this.ctxFactory = ctxFactory;
        this.templates = templates;
        this.stats = stats;
        this.history = history;
        this.retries = retries;
    }

    @GetMapping({"", "/", "/overview"})
    public ResponseEntity<String> overview(HttpServletRequest request) {
        return templates.page("pages/overview.jte", model(ctxFactory.page("overview", request)));
    }

    @GetMapping("/fragments/overview-tiles")
    public ResponseEntity<String> tilesFragment() {
        return templates.page("fragments/tiles.jte", Map.of(
                "tiles", stats.tiles(), "historyAvailable", stats.historyAvailable()));
    }

    @GetMapping("/fragments/overview-chart")
    public ResponseEntity<String> chartFragment() {
        return templates.page("fragments/chart.jte", Map.of(
                "chart", ChartVm.of(stats.throughputLast24h())));
    }

    @GetMapping("/fragments/overview-recent")
    public ResponseEntity<String> recentFragment(HttpServletRequest request) {
        var ctx = ctxFactory.page("overview", request);
        var failures = recentFailures();
        return templates.page("fragments/recentFailures.jte", Map.of(
                "ctx", ctx,
                "failures", failures,
                "retries", retryOptions(ctx, failures)));
    }

    private Map<String, Object> model(PageCtx ctx) {
        var failures = recentFailures();
        return Map.of(
                "ctx", ctx,
                "tiles", stats.tiles(),
                "historyAvailable", stats.historyAvailable(),
                "chart", ChartVm.of(stats.throughputLast24h()),
                "failures", failures,
                "retries", retryOptions(ctx, failures));
    }

    private List<HistoryEntry> recentFailures() {
        return stats.historyAvailable() ? history.recentFailures(5) : List.of();
    }

    private Map<Long, RetryLookup.Option> retryOptions(PageCtx ctx, List<HistoryEntry> failures) {
        return retries == null || ctx.readOnly() ? Map.of() : retries.forHistory(failures);
    }
}
