package io.github.logicsatinn.dbscheduler.console.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class Fmt {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private Fmt() {}

    public static String ts(Instant i) {
        return i == null ? "—" : TS.format(i);
    }

    /**
     * Encodes a task or instance identifier for use as a query-string value. HTML-escaping,
     * which JTE applies, is not URL-component encoding: an id like {@code order+A&B} would
     * otherwise split the link into extra parameters. Spaces become {@code %20} rather than
     * URLEncoder's form-encoded {@code +}, which is ambiguous in a query string.
     */
    public static String url(String value) {
        return value == null ? ""
                : URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String duration(long ms) {
        if (ms < 1000) {
            return ms + " ms";
        }
        if (ms < 60_000) {
            return String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
        }
        return (ms / 60_000) + " m " + (ms % 60_000) / 1000 + " s";
    }
}
