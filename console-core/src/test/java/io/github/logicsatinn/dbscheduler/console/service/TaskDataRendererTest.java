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
    void trustedFormatterIsExplicitAndBounded() {
        var renderer = new TaskDataRenderer(bytes -> "trusted output", true);
        assertThat(renderer.render(new byte[] {1})).isEqualTo("trusted output");
        assertThat(renderer.render(new byte[TaskDataRenderer.MAX_BYTES + 1])).contains("exceeds preview limit");
        assertThat(new TaskDataRenderer(bytes -> { throw new IllegalStateException("secret"); }, true)
                .render(new byte[] {1})).isEqualTo("Preview unavailable");
    }

    static boolean callback;
    static class Payload implements java.io.Serializable {
        private void readObject(java.io.ObjectInputStream input) throws Exception {
            input.defaultReadObject(); callback = true;
        }
        public String toString() { callback = true; return "unsafe"; }
    }
    @Test
    void javaPayloadNeverExecutesCallbacks() {
        callback = false;
        byte[] bytes = Serializer.DEFAULT_JAVA_SERIALIZER.serialize(new Payload());
        assertThat(new TaskDataRenderer(null, true).render(bytes)).startsWith("binary (");
        assertThat(callback).isFalse();
    }

    @Test
    void longOutputIsCapped() {
        var r = new TaskDataRenderer(null, true);
        String result = r.render("x".repeat(20000).getBytes(StandardCharsets.UTF_8));
        assertThat(result).hasSize(10000 + "… (truncated)".length()).endsWith("… (truncated)");
    }
}
