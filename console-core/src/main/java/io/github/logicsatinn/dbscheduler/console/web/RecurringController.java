package io.github.logicsatinn.dbscheduler.console.web;

import io.github.logicsatinn.dbscheduler.console.service.RecurringTasksService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("${db-scheduler-console.base-path:/db-scheduler-console}")
public class RecurringController {

    private final PageCtxFactory ctxFactory;
    private final TemplateRenderer templates;
    private final RecurringTasksService service;

    public RecurringController(PageCtxFactory ctxFactory, TemplateRenderer templates,
            RecurringTasksService service) {
        this.ctxFactory = ctxFactory;
        this.templates = templates;
        this.service = service;
    }

    @GetMapping("/recurring")
    public ResponseEntity<String> recurring(HttpServletRequest request) {
        return templates.page("pages/recurring.jte", Map.of(
                "ctx", ctxFactory.page("recurring", request),
                "rows", service.rows()));
    }
}
