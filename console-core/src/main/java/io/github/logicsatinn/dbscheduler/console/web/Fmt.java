package io.github.logicsatinn.dbscheduler.console.web;

import io.github.logicsatinn.dbscheduler.console.data.ExecutionState;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
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

    public static String url(String value) {
        return value == null ? ""
                : URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String state(ExecutionState state) {
        return switch (state) {
            case SCHEDULED -> "Scheduled";
            case DUE -> "Due";
            case RUNNING -> "Running";
            case RETRYING -> "Retrying";
            case FAILED -> "Parked";
        };
    }

    public static String outcome(HistoryEntry.Outcome outcome) {
        return switch (outcome) {
            case SUCCEEDED -> "Succeeded";
            case FAILED -> "Failed";
        };
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
