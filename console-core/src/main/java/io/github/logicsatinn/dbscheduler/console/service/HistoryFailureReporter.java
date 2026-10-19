package io.github.logicsatinn.dbscheduler.console.service;

@FunctionalInterface
public interface HistoryFailureReporter {

    HistoryFailureReporter NOOP = failure -> { };

    void historyWriteFailed(Throwable failure);
}
