package io.github.logicsatinn.dbscheduler.console.web;

import io.github.logicsatinn.dbscheduler.console.data.ExecutionFilter;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionState;
import io.github.logicsatinn.dbscheduler.console.data.SortColumn;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.StatsService;
import io.github.logicsatinn.dbscheduler.console.service.TaskDataRenderer;
import io.github.logicsatinn.dbscheduler.console.service.ExecutionsService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("${db-scheduler-console.base-path:/db-scheduler-console}")
public class ExecutionsController {

    private final PageCtxFactory ctxFactory;
    private final TemplateRenderer templates;
    private final ExecutionsService executions;
    private final HistoryRepository history;
    private final TaskDataRenderer taskData;
    private final StatsService stats;
    private final Clock clock;

    public ExecutionsController(PageCtxFactory ctxFactory, TemplateRenderer templates,
            ExecutionsService executions, HistoryRepository history,
            TaskDataRenderer taskData, StatsService stats, Clock clock) {
        this.ctxFactory = ctxFactory;
        this.templates = templates;
        this.executions = executions;
        this.history = history;
        this.taskData = taskData;
        this.stats = stats;
        this.clock = clock;
    }

    @GetMapping("/executions")
    public ResponseEntity<String> executions(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String task,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "EXECUTION_TIME") String sort,
            @RequestParam(defaultValue = "asc") String dir,
            HttpServletRequest request) {
        Instant now = clock.instant();
        SortColumn sortColumn = parseEnum(SortColumn.class, sort, SortColumn.EXECUTION_TIME);
        boolean desc = "desc".equalsIgnoreCase(dir);
        var filter = new ExecutionFilter(
                parseEnum(ExecutionState.class, state, null),
                blankToNull(task), blankToNull(q),
                parseLocal(from), parseLocal(to),
                Math.max(0, page), clamp(size, 1, 200), sortColumn, desc);

        Map<String, Object> model = new HashMap<>();
        model.put("ctx", ctxFactory.page("executions", request));
        model.put("filter", filter);
        model.put("stateParam", state == null ? "" : state);
        model.put("fromParam", from == null ? "" : from);
        model.put("toParam", to == null ? "" : to);
        model.put("page", executions.page(filter, now));
        model.put("taskNames", executions.distinctTaskNames());
        model.put("now", now);
        model.put("queryBase", queryBase(state, task, q, from, to, size));
        model.put("refreshUrl", request.getRequestURL() + queryBase(state, task, q, from, to, size)
                + "&sort=" + sortColumn.name() + "&dir=" + (desc ? "desc" : "asc")
                + "&page=" + Math.max(0, page));
        return templates.page("pages/executions.jte", model);
    }

    @GetMapping("/execution")
    public ResponseEntity<String> detail(@RequestParam String task, @RequestParam String id,
            HttpServletRequest request) {
        var row = executions.find(task, id);
        if (row.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                    .body("<p>Execution not found: it may have completed and left the live table.</p>");
        }
        Map<String, Object> model = new HashMap<>();
        model.put("ctx", ctxFactory.page("executions", request));
        model.put("row", row.get());
        model.put("now", clock.instant());
        model.put("dataPreview", taskData.render(row.get().taskData()));
        model.put("historyEntries", stats.historyAvailable()
                ? history.forInstance(task, id, 50) : List.of());
        model.put("selfUrl", ctxFactory.page("executions", request).basePath()
                + "/execution?task=" + Fmt.url(task) + "&id=" + Fmt.url(id));
        return templates.page("pages/executionDetail.jte", model);
    }

    /** Query string with every filter except page — pagination/sort links append to it. */
    private static String queryBase(String state, String task, String q, String from, String to, int size) {
        StringBuilder sb = new StringBuilder("?size=").append(size);
        appendParam(sb, "state", state);
        appendParam(sb, "task", task);
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

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
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

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static int clamp(int v, int min, int max) {
        return Math.min(max, Math.max(min, v));
    }
}
