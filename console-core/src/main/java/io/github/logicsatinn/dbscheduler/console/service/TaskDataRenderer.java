package io.github.logicsatinn.dbscheduler.console.service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public class TaskDataRenderer {
    public static final int MAX_BYTES = 65536;
    static final int MAX_CHARS = 10000;
    private final TrustedTaskDataFormatter formatter;
    private final boolean visible;

    public TaskDataRenderer(TrustedTaskDataFormatter formatter, boolean visible) {
        this.formatter = formatter;
        this.visible = visible;
    }
    public boolean visible() { return visible; }
    public String render(byte[] data) {
        if (!visible) return "(hidden)";
        if (data == null || data.length == 0) return "—";
        if (data.length > MAX_BYTES) return "Payload exceeds preview limit (64 KiB)";
        if (formatter != null) {
            try { return cap(formatter.format(data)); }
            catch (RuntimeException e) { return "Preview unavailable"; }
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
            if (text.chars().allMatch(c -> c >= 32 && c != 127 || c == '\n' || c == '\r' || c == '\t'))
                return cap(text);
        } catch (CharacterCodingException ignored) { }
        return "binary (" + data.length + " bytes)";
    }
    private static String cap(String text) {
        if (text == null) return "—";
        return text.length() <= MAX_CHARS ? text : text.substring(0, MAX_CHARS) + "… (truncated)";
    }
}
