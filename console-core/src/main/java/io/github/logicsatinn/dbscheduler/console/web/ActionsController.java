package io.github.logicsatinn.dbscheduler.console.web;

import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import io.github.logicsatinn.dbscheduler.console.service.ExecutionActions;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
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

    private final ConsoleProperties props;
    private final TemplateRenderer templates;
    private final ExecutionActions actions;

    public ActionsController(ConsoleProperties props, TemplateRenderer templates,
            ExecutionActions actions) {
        this.props = props;
        this.templates = templates;
        this.actions = actions;
    }

    @PostMapping("/actions/run-now")
    public ResponseEntity<String> runNow(@RequestParam String task, @RequestParam String id) {
        if (props.isReadOnly()) {
            return readOnly();
        }
        return flash(actions.runNow(task, id));
    }

    @PostMapping("/actions/reschedule")
    public ResponseEntity<String> reschedule(@RequestParam String task, @RequestParam String id,
            @RequestParam String when) {
        if (props.isReadOnly()) {
            return readOnly();
        }
        try {
            var instant = LocalDateTime.parse(when).atZone(ZoneId.systemDefault()).toInstant();
            return flash(actions.reschedule(task, id, instant));
        } catch (DateTimeParseException e) {
            return flash(new ExecutionActions.ActionResult(false, "Invalid date/time: " + when));
        }
    }

    @PostMapping("/actions/delete")
    public ResponseEntity<String> delete(@RequestParam String task, @RequestParam String id) {
        if (props.isReadOnly()) {
            return readOnly();
        }
        return flash(actions.delete(task, id));
    }

    @PostMapping("/actions/bulk")
    public ResponseEntity<String> bulk(@RequestParam String kind,
            @RequestParam(name = "ref", required = false) List<String> refs) {
        if (props.isReadOnly()) {
            return readOnly();
        }
        if (refs == null || refs.isEmpty()) {
            return flash(new ExecutionActions.ActionResult(false, "Nothing selected"));
        }
        var parsed = refs.stream().map(ExecutionActions.InstanceRef::parse).toList();
        var actionKind = ExecutionActions.Kind.valueOf(kind);
        return flash(actions.bulk(actionKind, parsed));
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
