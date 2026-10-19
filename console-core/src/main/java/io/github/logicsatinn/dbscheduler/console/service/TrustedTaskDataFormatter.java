package io.github.logicsatinn.dbscheduler.console.service;

/** Explicit host opt-in. Input is size-limited; output is escaped as text, never HTML.
 * Implementations must not deserialize untrusted objects without a reviewed filtering policy. */
@FunctionalInterface
public interface TrustedTaskDataFormatter {
    String format(byte[] data);
}
