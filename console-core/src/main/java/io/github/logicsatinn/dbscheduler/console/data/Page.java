package io.github.logicsatinn.dbscheduler.console.data;

import java.util.List;

/** A bounded keyset slice; no count query or numbered offset. */
public record Page<T>(List<T> items, String previousCursor, String nextCursor) {}
