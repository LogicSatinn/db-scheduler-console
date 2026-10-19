package io.github.logicsatinn.dbscheduler.console.service;

import static org.assertj.core.api.Assertions.*;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import java.io.*;
import org.junit.jupiter.api.Test;

class FilteringJavaSerializerTest {
    static boolean callback;
    static class Untrusted implements Serializable {
        private void readObject(ObjectInputStream input) throws Exception {
            input.defaultReadObject(); callback = true;
        }
    }
    record Allowed(String value) implements Serializable {}
    record Link(Link next) implements Serializable {}

    @Test void rejectsUnknownClassBeforeCallbackAndPreservesCompatibilityChecks() {
        callback = false;
        var serializer = new FilteringJavaSerializer(ObjectInputFilter.Config.createFilter(Allowed.class.getName() + ";!*"));
        assertThatThrownBy(() -> serializer.deserialize(Object.class,
                Serializer.DEFAULT_JAVA_SERIALIZER.serialize(new Untrusted())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(callback).isFalse();
        byte[] data = serializer.serialize(new Allowed("ok"));
        assertThat(serializer.deserialize(Allowed.class, data)).isEqualTo(new Allowed("ok"));
        assertThatThrownBy(() -> serializer.deserialize(String.class, data)).isInstanceOf(ClassCastException.class);
    }
    @Test void boundsArraysDepthReferencesAndInputSize() {
        var serializer = new FilteringJavaSerializer(info -> ObjectInputFilter.Status.ALLOWED);
        assertThatThrownBy(() -> serializer.deserialize(Object.class, serializer.serialize(new int[10001])))
                .isInstanceOf(IllegalArgumentException.class);
        Link link = null;
        for (int i = 0; i < 25; i++) link = new Link(link);
        byte[] deep = serializer.serialize(link);
        assertThatThrownBy(() -> serializer.deserialize(Link.class, deep)).isInstanceOf(IllegalArgumentException.class);
        Object[] references = new Object[10000];
        for (int i = 0; i < references.length; i++) references[i] = new Allowed("ok");
        byte[] graph = serializer.serialize(references);
        assertThatThrownBy(() -> serializer.deserialize(Object[].class, graph)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> serializer.deserialize(Object.class, new byte[FilteringJavaSerializer.MAX_INPUT_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
