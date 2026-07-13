@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param io.github.logicsatinn.dbscheduler.console.data.ExecutionRow row
@param java.time.Instant now
@param String dataPreview
@param java.util.List<io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry> historyEntries
@template.layout(ctx = ctx, title = row.taskName() + " / " + row.instanceId(), content = @`
    <h1>${row.taskName()} <span class="muted">/</span> ${row.instanceId()}</h1>
    <div class="card">
        <h2>Execution</h2>
        <table class="data">
            <tbody>
            <tr><th>State</th><td><span class="state ${row.state(now).name().toLowerCase()}">${row.state(now).name()}</span></td></tr>
            <tr><th>Execution time</th><td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(row.executionTime())}</td></tr>
            <tr><th>Picked by</th><td>${row.pickedBy()}</td></tr>
            <tr><th>Consecutive failures</th><td class="num">${row.consecutiveFailures()}</td></tr>
            <tr><th>Last success</th><td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(row.lastSuccess())}</td></tr>
            <tr><th>Last failure</th><td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(row.lastFailure())}</td></tr>
            <tr><th>Version</th><td class="num">${row.version()}</td></tr>
            </tbody>
        </table>
    </div>
    <div class="card">
        <h2>Task data</h2>
        <pre class="data">${dataPreview}</pre>
    </div>
    <div class="card">
        <h2>History</h2>
        @if(historyEntries.isEmpty())
            <div class="empty">No recorded history for this instance.</div>
        @else
            <table class="data">
                <thead><tr><th>Outcome</th><th>Started</th><th>Duration</th><th>Node</th><th>Error</th></tr></thead>
                <tbody>
                @for(var h : historyEntries)
                    <tr>
                        <td><span class="state ${h.outcome().name().toLowerCase()}">${h.outcome().name()}</span></td>
                        <td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(h.startedAt())}</td>
                        <td class="num">${io.github.logicsatinn.dbscheduler.console.web.Fmt.duration(h.durationMs())}</td>
                        <td>${h.pickedBy()}</td>
                        <td>
                            @if(h.stacktrace() != null)
                                <details class="stack"><summary>${h.exceptionMessage()}</summary><pre>${h.stacktrace()}</pre></details>
                            @else
                                ${h.exceptionMessage()}
                            @endif
                        </td>
                    </tr>
                @endfor
                </tbody>
            </table>
        @endif
    </div>
`)
