package io.github.logicsatinn.dbscheduler.console.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.serializer.Serializer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TaskDataRendererTest {

    @Test
    void hiddenModeShowsNothing() {
        var r = new TaskDataRenderer(null, false);
        assertThat(r.render("{\"a\":1}".getBytes(StandardCharsets.UTF_8))).isEqualTo("(hidden)");
    }

    @Test
    void nullAndEmptyShowDash() {
        var r = new TaskDataRenderer(null, true);
        assertThat(r.render(null)).isEqualTo("—");
        assertThat(r.render(new byte[0])).isEqualTo("—");
    }

    @Test
    void printableUtf8IsShownAsText() {
        var r = new TaskDataRenderer(null, true);
        assertThat(r.render("{\"orderId\":42}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("{\"orderId\":42}");
    }

    @Test
    void binaryFallsBackToByteCount() {
        var r = new TaskDataRenderer(null, true);
        byte[] javaSerialized = {(byte) 0xAC, (byte) 0xED, 0x00, 0x05, 0x01, 0x02};
        assertThat(r.render(javaSerialized)).isEqualTo("binary (6 bytes)");
    }

    @Test
    void serializerWinsWhenItWorks() {
        Serializer serializer = new Serializer() {
            @Override public byte[] serialize(Object data) { return new byte[0]; }
            @Override @SuppressWarnings("unchecked")
            public <T> T deserialize(Class<T> clazz, byte[] data) { return (T) "MyData[id=7]"; }
        };
        var r = new TaskDataRenderer(serializer, true);
        assertThat(r.render(new byte[] {1})).isEqualTo("MyData[id=7]");
    }

    @Test
    void throwingSerializerFallsThrough() {
        Serializer serializer = new Serializer() {
            @Override public byte[] serialize(Object data) { return new byte[0]; }
            @Override public <T> T deserialize(Class<T> clazz, byte[] data) {
                throw new IllegalStateException("unknown class");
            }
        };
        var r = new TaskDataRenderer(serializer, true);
        assertThat(r.render("plain text".getBytes(StandardCharsets.UTF_8))).isEqualTo("plain text");
    }

    @Test
    void longOutputIsCapped() {
        var r = new TaskDataRenderer(null, true);
        String result = r.render("x".repeat(20000).getBytes(StandardCharsets.UTF_8));
        assertThat(result).hasSize(10000 + "… (truncated)".length()).endsWith("… (truncated)");
    }
}
