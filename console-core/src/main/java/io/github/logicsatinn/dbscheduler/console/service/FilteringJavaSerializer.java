package io.github.logicsatinn.dbscheduler.console.service;

import com.github.kagkarlsson.scheduler.serializer.Serializer;
import java.io.*;
import java.util.Objects;

/** Host-configured allowlist for Java payloads. Unlisted classes are rejected, not implicitly allowed. */
public final class FilteringJavaSerializer implements Serializer {
    public static final int MAX_INPUT_BYTES = 1024 * 1024;
    private final ObjectInputFilter allowlist;
    public FilteringJavaSerializer(ObjectInputFilter allowlist) {
        this.allowlist = Objects.requireNonNull(allowlist);
    }
    @Override public byte[] serialize(Object value) {
        return Serializer.DEFAULT_JAVA_SERIALIZER.serialize(value);
    }
    @Override public <T> T deserialize(Class<T> type, byte[] bytes) {
        if (bytes == null) return null;
        if (bytes.length > MAX_INPUT_BYTES) throw new IllegalArgumentException("Payload exceeds input limit");
        try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            ObjectInputFilter limitsAndAllowlist = info -> {
                if (info.depth() > 20 || info.references() > 10000 || info.arrayLength() > 10000
                        || info.streamBytes() > MAX_INPUT_BYTES) return ObjectInputFilter.Status.REJECTED;
                ObjectInputFilter.Status decision = allowlist.checkInput(info);
                if (info.serialClass() != null && decision != ObjectInputFilter.Status.ALLOWED)
                    return ObjectInputFilter.Status.REJECTED;
                return decision;
            };
            ObjectInputFilter existing = in.getObjectInputFilter();
            in.setObjectInputFilter(existing == null ? limitsAndAllowlist
                    : ObjectInputFilter.merge(existing, limitsAndAllowlist));
            return type.cast(in.readObject());
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalArgumentException("Payload rejected by deserialization policy", e);
        }
    }
}
