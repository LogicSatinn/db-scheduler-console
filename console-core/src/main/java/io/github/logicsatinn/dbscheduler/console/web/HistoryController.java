package io.github.logicsatinn.dbscheduler.console.web;

import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryFilter;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.TaskDataRenderer;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("${db-scheduler-console.base-path:/db-scheduler-console}")
public class HistoryController {

    private final PageCtxFactory ctxFactory;
    private final TemplateRenderer templates;
    private final HistoryRepository history;
    private final Dialect dialect;
    private final TaskDataRenderer taskData;

    public HistoryController(PageCtxFactory ctxFactory, TemplateRenderer templates,
            HistoryRepository history, Dialect dialect, TaskDataRenderer taskData) {
        this.ctxFactory = ctxFactory;
        this.templates = templates;
        this.history = history;
        this.dialect = dialect;
        this.taskData = taskData;
    }

    public HistoryController(PageCtxFactory ctxFactory, TemplateRenderer templates,
            HistoryRepository history, Dialect dialect) {
        this(ctxFactory, templates, history, dialect, new TaskDataRenderer(null, true));
    }

    @GetMapping("/history")
    public ResponseEntity<String> history(
            @RequestParam(required = false) String task,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            HttpServletRequest request) {
        var ctx = ctxFactory.page("history", request);
        HistoryRepository.Schema schema = history.schema();
        if (schema == HistoryRepository.Schema.MISSING) {
            return templates.page("pages/historySetup.jte", Map.of(
                    "ctx", ctx,
                    "dialectName", dialect.name(),
                    "legacy", false,
                    "script", history.createTableScript()));
        }
        HistoryEntry.Outcome parsedOutcome = null;
        if (outcome != null && !outcome.isBlank()) {
            try {
                parsedOutcome = HistoryEntry.Outcome.valueOf(outcome.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // unknown value = no outcome filter
            }
        }
        var filter = new HistoryFilter(
                blankToNull(task), parsedOutcome, blankToNull(q),
                parseLocal(from), parseLocal(to),
                Math.max(0, page), Math.min(200, Math.max(1, size)));

        Map<String, Object> model = new HashMap<>();
        model.put("ctx", ctx);
        model.put("filter", filter);
        model.put("outcomeParam", outcome == null ? "" : outcome);
        model.put("fromParam", from == null ? "" : from);
        model.put("toParam", to == null ? "" : to);
        model.put("page", history.page(filter));
        model.put("taskData", taskData);
        model.put("legacy", schema == HistoryRepository.Schema.LEGACY);
        model.put("upgradeScript", schema == HistoryRepository.Schema.LEGACY
                ? history.upgradeTableScript() : "");
        model.put("queryBase", queryBase(task, outcome, q, from, to, size));
        return templates.page("pages/history.jte", model);
    }

    private static String queryBase(String task, String outcome, String q, String from, String to, int size) {
        StringBuilder sb = new StringBuilder("?size=").append(size);
        appendParam(sb, "task", task);
        appendParam(sb, "outcome", outcome);
        appendParam(sb, "q", q);
        appendParam(sb, "from", from);
        appendParam(sb, "to", to);
        return sb.toString();
    }

    private static void appendParam(StringBuilder sb, String name, String value) {
        if (value != null && !value.isBlank()) {
            sb.append('&').append(name).append('=')
              .append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private Instant parseLocal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
