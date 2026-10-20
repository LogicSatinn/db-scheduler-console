package io.github.logicsatinn.dbscheduler.console.web;

import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionFilter;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionRow;
import io.github.logicsatinn.dbscheduler.console.data.ExecutionState;
import io.github.logicsatinn.dbscheduler.console.data.Page;
import io.github.logicsatinn.dbscheduler.console.service.ExecutionActions;
import io.github.logicsatinn.dbscheduler.console.service.ExecutionsService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("${db-scheduler-console.base-path:/db-scheduler-console}")
public class ActionsController {

    public static final int MAX_RETRY_MATCHING = 1000;

    private final ConsoleProperties props;
    private final TemplateRenderer templates;
    private final ExecutionActions actions;
    private final ExecutionsService executions;
    private final Clock clock;

    public ActionsController(ConsoleProperties props, TemplateRenderer templates,
            ExecutionActions actions) {
        this(props, templates, actions, null, Clock.systemUTC());
    }

    public ActionsController(ConsoleProperties props, TemplateRenderer templates,
            ExecutionActions actions, ExecutionsService executions, Clock clock) {
        this.props = props;
        this.templates = templates;
        this.actions = actions;
        this.executions = executions;
        this.clock = clock;
    }

    @PostMapping("/actions/run-now")
    public ResponseEntity<String> runNow(@RequestParam String task, @RequestParam String id) {
        if (props.isReadOnly()) {
            return readOnly();
        }
        validateRef(task, id);
        return flash(actions.runNow(task, id));
    }

    @PostMapping("/actions/retry")
    public ResponseEntity<String> retry(@RequestParam String task, @RequestParam String id) {
        if (props.isReadOnly()) {
            return readOnly();
        }
        validateRef(task, id);
        return flash(actions.retry(task, id));
    }

    @PostMapping("/actions/retry-matching")
    public ResponseEntity<String> retryMatching(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String task,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        if (props.isReadOnly()) {
            return readOnly();
        }
        ExecutionFilter filter = ExecutionsController.filter(state, task, type, q, from, to,
                null, 200, null, "asc");
        if (filter.state() != ExecutionState.RETRYING && filter.state() != ExecutionState.FAILED)
            throw new IllegalArgumentException("Retry all needs the Retrying or Parked state filter");
        if (executions == null) {
            return flash(new ExecutionActions.ActionResult(false, "Retry all is not available"));
        }
        Instant now = clock.instant();
        List<ExecutionActions.InstanceRef> refs = new ArrayList<>();
        String cursor = null;
        do {
            Page<ExecutionRow> page = executions.page(new ExecutionFilter(filter.state(), filter.taskName(),
                    filter.instanceContains(), filter.from(), filter.to(), cursor, filter.pageSize(),
                    filter.sort(), filter.descending(), filter.kind()), now);
            for (ExecutionRow row : page.items()) {
                refs.add(new ExecutionActions.InstanceRef(row.taskName(), row.instanceId()));
            }
            cursor = page.nextCursor();
        } while (cursor != null && refs.size() <= MAX_RETRY_MATCHING);
        if (refs.isEmpty()) {
            return flash(new ExecutionActions.ActionResult(false, "No executions match these filters"));
        }
        boolean more = refs.size() > MAX_RETRY_MATCHING;
        var result = actions.bulk(ExecutionActions.Kind.RETRY,
                more ? refs.subList(0, MAX_RETRY_MATCHING) : refs);
        return flash(more
                ? new ExecutionActions.ActionResult(result.success(), result.message()
                        + " — only the first " + MAX_RETRY_MATCHING
                        + " matching executions were retried; apply again for the rest")
                : result);
    }

    @PostMapping("/actions/rerun")
    public ResponseEntity<String> rerun(@RequestParam long history) {
        if (props.isReadOnly()) {
            return readOnly();
        }
        if (history <= 0) throw new IllegalArgumentException("Invalid history id");
        return flash(actions.rerun(history));
    }

    @PostMapping("/actions/reschedule")
    public ResponseEntity<String> reschedule(@RequestParam String task, @RequestParam String id,
            @RequestParam String when) {
        if (props.isReadOnly()) {
            return readOnly();
        }
        validateRef(task, id);
        try {
            var instant = LocalDateTime.parse(when).atZone(ZoneId.systemDefault()).toInstant();
            return flash(actions.reschedule(task, id, instant));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date/time");
        }
    }

    @PostMapping("/actions/delete")
    public ResponseEntity<String> delete(@RequestParam String task, @RequestParam String id) {
        if (props.isReadOnly()) {
            return readOnly();
        }
        validateRef(task, id);
        return flash(actions.delete(task, id));
    }

    @PostMapping("/actions/bulk")
    public ResponseEntity<String> bulk(@RequestParam String kind,
            @RequestParam(name = "ref", required = false) List<String> refs) {
        if (props.isReadOnly()) {
            return readOnly();
        }
        ExecutionActions.Kind actionKind;
        try { actionKind = ExecutionActions.Kind.valueOf(kind); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid action"); }
        if (refs == null || refs.isEmpty() || refs.size() > 200)
            throw new IllegalArgumentException("Select between 1 and 200 executions");
        var parsed = refs.stream().map(ExecutionActions.InstanceRef::parse).distinct().toList();
        parsed.forEach(ref -> validateRef(ref.taskName(), ref.instanceId()));
        return flash(actions.bulk(actionKind, parsed));
    }

    private static void validateRef(String task, String id) {
        if (task == null || task.isBlank() || task.length() > 100 || task.contains("::")
                || id == null || id.isBlank() || id.length() > 100)
            throw new IllegalArgumentException("Invalid execution reference");
    }

    private ResponseEntity<String> flash(ExecutionActions.ActionResult result) {
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .header("HX-Trigger", "dsc-refresh")
                .body(templates.render("fragments/flash.jte", Map.of("result", result)));
    }

    private ResponseEntity<String> readOnly() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(templates.render("fragments/flash.jte", Map.of("result",
                        new ExecutionActions.ActionResult(false,
                                "The console is in read-only mode — actions are disabled"))));
    }
}
