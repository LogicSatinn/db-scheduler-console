package io.github.logicsatinn.dbscheduler.console.data;

/** Host-owned redaction for persisted and displayed exception messages and stack traces.
 * Receives non-null text. Implementations should be deterministic and must not log the input. */
@FunctionalInterface
public interface DiagnosticSanitizer {
    DiagnosticSanitizer IDENTITY = text -> text;
    String sanitize(String text);

    static String apply(DiagnosticSanitizer sanitizer, String text) {
        if (text == null) return null;
        try { return sanitizer.sanitize(text); }
        catch (RuntimeException error) { return "[diagnostic redacted]"; }
    }
}
