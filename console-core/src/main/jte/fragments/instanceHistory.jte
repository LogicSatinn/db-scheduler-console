@param java.util.List<io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry> historyEntries
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
