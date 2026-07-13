@param java.util.List<io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry> failures
@param String basePath
@if(failures.isEmpty())
    <div class="empty">No recent failures.</div>
@else
    <table class="data" id="recent-failures">
        <thead><tr><th>Task</th><th>Instance</th><th>When</th><th>Error</th></tr></thead>
        <tbody>
        @for(var f : failures)
            <tr>
                <td>${f.taskName()}</td>
                <td><a href="${basePath}/execution?task=${f.taskName()}&id=${f.instanceId()}">${f.instanceId()}</a></td>
                <td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(f.startedAt())}</td>
                <td>${f.exceptionMessage()}</td>
            </tr>
        @endfor
        </tbody>
    </table>
@endif
