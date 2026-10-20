@import io.github.logicsatinn.dbscheduler.console.web.Fmt
@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param java.util.List<io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry> failures
@param java.util.Map<Long, io.github.logicsatinn.dbscheduler.console.service.RetryLookup.Option> retries = java.util.Map.of()
@if(failures.isEmpty())
    <div class="empty">No recent failures.</div>
@else
    <table class="data" id="recent-failures">
        <thead><tr><th>Task</th><th>Instance ID</th><th>Started at</th><th>Error</th>@if(!ctx.readOnly())<th></th>@endif</tr></thead>
        <tbody>
        @for(var f : failures)
            <tr>
                <td>${f.taskName()}</td>
                <td><a href="${ctx.basePath()}/execution?task=${Fmt.url(f.taskName())}&id=${Fmt.url(f.instanceId())}">${f.instanceId()}</a></td>
                <td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(f.startedAt())}</td>
                <td>${f.exceptionMessage()}</td>
                @if(!ctx.readOnly())
                    <td>@template.fragments.retryAction(ctx = ctx, entry = f, option = retries.get(f.id()))</td>
                @endif
            </tr>
        @endfor
        </tbody>
    </table>
@endif
