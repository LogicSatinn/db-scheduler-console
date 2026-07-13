package io.github.logicsatinn.dbscheduler.console.data;

import java.util.List;

public record Page<T>(List<T> items, int page, int pageSize, long total) {
    public int totalPages() {
        return (int) Math.ceil((double) total / pageSize);
    }
}
