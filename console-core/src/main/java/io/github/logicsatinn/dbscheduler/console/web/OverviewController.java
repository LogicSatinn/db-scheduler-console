package io.github.logicsatinn.dbscheduler.console.web;

import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.StatsService;
import jakarta.servlet.http.HttpServletRequest;
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

    public OverviewController(PageCtxFactory ctxFactory, TemplateRenderer templates,
            StatsService stats, HistoryRepository history) {
        this.ctxFactory = ctxFactory;
        this.templates = templates;
        this.stats = stats;
        this.history = history;
    }

    @GetMapping({"", "/", "/overview"})
    public ResponseEntity<String> overview(HttpServletRequest request) {
        return templates.page("pages/overview.jte", Map.of(
                "ctx", ctxFactory.page("overview", request),
                "tiles", stats.tiles(),
                "historyAvailable", stats.historyAvailable()));
    }
}
