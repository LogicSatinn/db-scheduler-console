package io.github.logicsatinn.dbscheduler.console.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry;
import io.github.logicsatinn.dbscheduler.console.data.history.HistoryRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ConsoleSchedulerListenerTest {

    final HistoryRepository repo = mock(HistoryRepository.class);
    final ConsoleSchedulerListener listener = new ConsoleSchedulerListener(repo);

    static Execution execution() {
        return new Execution(Instant.parse("2026-07-13T12:00:00Z"),
                new TaskInstance<>("email", "42"));
    }

    @Test
    void recordsSuccess() {
        Instant started = Instant.parse("2026-07-13T12:00:00Z");
        Instant done = started.plusSeconds(3);
        listener.onExecutionComplete(ExecutionComplete.success(execution(), started, done));

        var captor = ArgumentCaptor.forClass(HistoryEntry.class);
        verify(repo).insert(captor.capture());
        var e = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(e.taskName()).isEqualTo("email");
        org.assertj.core.api.Assertions.assertThat(e.instanceId()).isEqualTo("42");
        org.assertj.core.api.Assertions.assertThat(e.outcome()).isEqualTo(HistoryEntry.Outcome.SUCCEEDED);
        org.assertj.core.api.Assertions.assertThat(e.durationMs()).isEqualTo(3000);
        org.assertj.core.api.Assertions.assertThat(e.startedAt()).isEqualTo(started);
        org.assertj.core.api.Assertions.assertThat(e.finishedAt()).isEqualTo(done);
        org.assertj.core.api.Assertions.assertThat(e.exceptionClass()).isNull();
    }

    @Test
    void recordsFailureWithCause() {
        Instant started = Instant.parse("2026-07-13T12:00:00Z");
        listener.onExecutionComplete(ExecutionComplete.failure(
                execution(), started, started.plusSeconds(1),
                new IllegalStateException("boom")));

        var captor = ArgumentCaptor.forClass(HistoryEntry.class);
        verify(repo).insert(captor.capture());
        var e = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(e.outcome()).isEqualTo(HistoryEntry.Outcome.FAILED);
        org.assertj.core.api.Assertions.assertThat(e.exceptionClass()).isEqualTo("java.lang.IllegalStateException");
        org.assertj.core.api.Assertions.assertThat(e.exceptionMessage()).isEqualTo("boom");
        org.assertj.core.api.Assertions.assertThat(e.stacktrace()).contains("IllegalStateException");
    }

    @Test
    void neverThrowsWhenRepositoryFails() {
        doThrow(new RuntimeException("db down")).when(repo).insert(any());
        Instant started = Instant.now();
        assertThatCode(() -> listener.onExecutionComplete(
                ExecutionComplete.success(execution(), started, started.plusSeconds(1))))
                .doesNotThrowAnyException();
    }

    @Test
    void neverThrowsWhenRepositoryRaisesAnError() {
        doThrow(new NoClassDefFoundError("jdbc driver")).when(repo).insert(any());
        Instant started = Instant.now();
        assertThatCode(() -> listener.onExecutionComplete(
                ExecutionComplete.success(execution(), started, started.plusSeconds(1))))
                .doesNotThrowAnyException();
    }
}
