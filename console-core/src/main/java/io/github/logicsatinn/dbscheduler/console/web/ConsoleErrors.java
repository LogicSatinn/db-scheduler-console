package io.github.logicsatinn.dbscheduler.console.web;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice(assignableTypes = {ActionsController.class, ExecutionsController.class,
        HistoryController.class, OverviewController.class, RecurringController.class})
public final class ConsoleErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ConsoleErrors.class);
    @ExceptionHandler({IllegalArgumentException.class,
            org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<String> invalid(Exception error) {
        return ResponseEntity.badRequest().header("Cache-Control", "no-store")
                .body("Invalid console request");
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> unexpected(Exception error) {
        String id = UUID.randomUUID().toString();
        // Exception messages and stack traces can contain SQL, credentials and task data.
        LOG.warn("Console request failed: reference={}, exceptionType={}", id, error.getClass().getName());
        return ResponseEntity.internalServerError().header("Cache-Control", "no-store")
                .body("Console request failed. Reference: " + id);
    }
}
