package io.github.logicsatinn.dbscheduler.console.service;

/** Optional hook for observing best-effort history write failures. */
@FunctionalInterface
public interface HistoryFailureReporter {

    HistoryFailureReporter NOOP = failure -> { };

    void historyWriteFailed(Throwable failure);
}
