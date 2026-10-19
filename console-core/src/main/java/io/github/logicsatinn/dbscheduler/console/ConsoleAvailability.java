package io.github.logicsatinn.dbscheduler.console;

import io.github.logicsatinn.dbscheduler.console.data.Dialect;

public record ConsoleAvailability(Dialect dialect) {

    public boolean available() {
        return dialect != null;
    }

    public Dialect dialectOrFallback() {
        return dialect != null ? dialect : Dialect.H2;
    }
}
