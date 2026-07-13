package io.github.logicsatinn.dbscheduler.console.service;

import com.github.kagkarlsson.scheduler.serializer.Serializer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Renders task_data bytes for humans without pretending to decode what it cannot. */
public class TaskDataRenderer {

    static final int MAX_CHARS = 10000;

    private final Serializer serializer;
    private final boolean visible;

    public TaskDataRenderer(Serializer serializer, boolean visible) {
        this.serializer = serializer;
        this.visible = visible;
    }

    public String render(byte[] taskData) {
        if (!visible) {
            return "(hidden)";
        }
        if (taskData == null || taskData.length == 0) {
            return "—";
        }
        if (serializer != null) {
            try {
                Object o = serializer.deserialize(Object.class, taskData);
                if (o != null) {
                    return cap(String.valueOf(o));
                }
            } catch (RuntimeException ignored) {
                // fall through to the UTF-8 heuristic
            }
        }
        String text = tryPrintableUtf8(taskData);
        if (text != null) {
            return cap(text);
        }
        return "binary (" + taskData.length + " bytes)";
    }

    private static String tryPrintableUtf8(byte[] data) {
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString();
            boolean printable = decoded.chars()
                    .allMatch(c -> c >= 32 || c == '\n' || c == '\r' || c == '\t');
            return printable ? decoded : null;
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String cap(String s) {
        return s.length() <= MAX_CHARS ? s : s.substring(0, MAX_CHARS) + "… (truncated)";
    }
}
