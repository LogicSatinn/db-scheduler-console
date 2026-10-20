package io.github.logicsatinn.dbscheduler.console.web;

import io.github.logicsatinn.dbscheduler.console.data.Keyset;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionFilter;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionState;
import io.github.logicsatinn.dbscheduler.console.data.SortColumn;
import io.github.logicsatinn.dbscheduler.console.data.TaskKind;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import io.github.logicsatinn.dbscheduler.console.service.StatsService;
import io.github.logicsatinn.dbscheduler.console.service.TaskDataRenderer;
import io.github.logicsatinn.dbscheduler.console.service.ExecutionsService;
import io.github.logicsatinn.dbscheduler.console.service.RetryLookup;
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
    private final RetryLookup retries;

    public ExecutionsController(PageCtxFactory ctxFactory, TemplateRenderer templates,
            ExecutionsService executions, HistoryRepository history,
            TaskDataRenderer taskData, StatsService stats, Clock clock) {
        this(ctxFactory, templates, executions, history, taskData, stats, clock, null);
    }

    public ExecutionsController(PageCtxFactory ctxFactory, TemplateRenderer templates,
            ExecutionsService executions, HistoryRepository history,
            TaskDataRenderer taskData, StatsService stats, Clock clock, RetryLookup retries) {
        this.ctxFactory = ctxFactory;
        this.templates = templates;
        this.executions = executions;
        this.history = history;
        this.taskData = taskData;
        this.stats = stats;
        this.clock = clock;
        this.retries = retries;
    }

    @GetMapping("/executions")
    public ResponseEntity<String> executions(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String task,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "EXECUTION_TIME") String sort,
            @RequestParam(defaultValue = "asc") String dir,
            HttpServletRequest request) {
        Instant now = clock.instant();
        var filter = filter(state, task, type, q, from, to, cursor, size, sort, dir);
        SortColumn sortColumn = filter.sort();
        boolean desc = filter.descending();

        Map<String, Object> model = new HashMap<>();
        model.put("ctx", ctxFactory.page("executions", request));
        model.put("filter", filter);
        model.put("stateParam", state == null ? "" : state);
        model.put("typeParam", filter.kind() == null ? "" : filter.kind().name());
        model.put("fromParam", from == null ? "" : from);
        model.put("toParam", to == null ? "" : to);
        model.put("page", executions.page(filter, now));
        model.put("taskNames", executions.distinctTaskNames());
        model.put("now", now);
        model.put("queryBase", queryBase(state, task, type, q, from, to, size));
        model.put("refreshUrl", request.getRequestURI() + queryBase(state, task, type, q, from, to, size)
                + "&sort=" + sortColumn.name() + "&dir=" + (desc ? "desc" : "asc")
                + (cursor == null ? "" : "&cursor=" + Fmt.url(cursor)));
        return templates.page("pages/executions.jte", model);
    }

    @GetMapping("/execution")
    public ResponseEntity<String> detail(@RequestParam String task, @RequestParam String id,
            HttpServletRequest request) {
        var row = executions.find(task, id);
        var historyEntries = stats.historyAvailable() ? history.forInstance(task, id, 50) : List.<HistoryEntry>of();
        if (row.isEmpty()) {
            var ctx = ctxFactory.page("executions", request);
            Long rerunId = null;
            if (retries != null && !ctx.readOnly() && !historyEntries.isEmpty()) {
                var last = historyEntries.get(0);
                if (retries.forHistory(List.of(last)).get(last.id()) == RetryLookup.Option.RERUN) {
                    rerunId = last.id();
                }
            }
            Map<String, Object> gone = new HashMap<>();
            gone.put("ctx", ctx);
            gone.put("taskName", task);
            gone.put("instanceId", id);
            gone.put("historyEntries", historyEntries);
            gone.put("rerunId", rerunId);
            gone.put("selfUrl", ctx.basePath() + "/execution?task=" + Fmt.url(task) + "&id=" + Fmt.url(id));
            return ResponseEntity.status(historyEntries.isEmpty() ? HttpStatus.NOT_FOUND : HttpStatus.OK)
                    .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                    .body(templates.render("pages/executionGone.jte", gone));
        }
        Map<String, Object> model = new HashMap<>();
        model.put("ctx", ctxFactory.page("executions", request));
        model.put("row", row.get());
        model.put("now", clock.instant());
        model.put("payloadVisible", taskData.visible());
        model.put("historyEntries", historyEntries);
        model.put("selfUrl", ctxFactory.page("executions", request).basePath()
                + "/execution?task=" + Fmt.url(task) + "&id=" + Fmt.url(id));
        return templates.page("pages/executionDetail.jte", model);
    }

    @GetMapping(value = "/payload/execution", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> payload(@RequestParam String task, @RequestParam String id) {
        if (!taskData.visible()) return ResponseEntity.status(403).body("Payloads are hidden");
        return ResponseEntity.ok(taskData.render(executions.payload(task, id, TaskDataRenderer.MAX_BYTES)));
    }

    private static String queryBase(String state, String task, String type, String q,
            String from, String to, int size) {
        StringBuilder sb = new StringBuilder("?size=").append(size);
        appendParam(sb, "state", state);
        appendParam(sb, "task", task);
        appendParam(sb, "type", type);
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

    static ExecutionFilter filter(String state, String task, String type, String q, String from,
            String to, String cursor, int size, String sort, String dir) {
        SortColumn sortColumn = parseEnum(SortColumn.class, sort, SortColumn.EXECUTION_TIME);
        if (!"asc".equalsIgnoreCase(dir) && !"desc".equalsIgnoreCase(dir))
            throw new IllegalArgumentException("Invalid sort direction");
        return new ExecutionFilter(
                parseEnum(ExecutionState.class, state, null),
                blankToNull(task), blankToNull(q),
                parseLocal(from), parseLocal(to),
                cursor, Keyset.size(size), sortColumn, "desc".equalsIgnoreCase(dir),
                parseEnum(TaskKind.class, type, null));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid filter value");
        }
    }

    private static Instant parseLocal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date/time");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

}
