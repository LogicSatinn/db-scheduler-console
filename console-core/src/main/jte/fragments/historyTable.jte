@import io.github.logicsatinn.dbscheduler.console.web.Fmt
@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param io.github.logicsatinn.dbscheduler.console.data.Page<io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry> page
@param io.github.logicsatinn.dbscheduler.console.data.history.HistoryFilter filter
@param String queryBase
@param io.github.logicsatinn.dbscheduler.console.service.TaskDataRenderer taskData
@param java.util.Map<Long, io.github.logicsatinn.dbscheduler.console.service.RetryLookup.Option> retries = java.util.Map.of()
!{boolean live = filter.cursor() == null;}
<div id="history-region"
     hx-get="${live ? ctx.basePath() + "/history" + queryBase : null}"
     hx-trigger="${live ? "every " + ctx.pollSeconds() + "s, dsc-refresh from:body" : null}"
     hx-select="#history-region" hx-swap="outerHTML">
    <table class="data">
        <thead><tr><th>Task</th><th>Instance ID</th><th>Outcome</th><th>Started at</th><th>Duration</th><th>Node</th><th>Payload</th><th>Error</th>@if(!ctx.readOnly())<th>Actions</th>@endif</tr></thead>
        <tbody>
        @if(page.items().isEmpty())
            <tr><td colspan="${ctx.readOnly() ? 8 : 9}" class="empty">No runs match these filters.</td></tr>
        @endif
        @for(var h : page.items())
            <tr>
                <td>${h.taskName()}</td>
                <td><a href="${ctx.basePath()}/execution?task=${Fmt.url(h.taskName())}&id=${Fmt.url(h.instanceId())}">${h.instanceId()}</a></td>
                <td><span class="state ${h.outcome().name().toLowerCase()}">${io.github.logicsatinn.dbscheduler.console.web.Fmt.outcome(h.outcome())}</span></td>
                <td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(h.startedAt())}</td>
                <td class="num">${io.github.logicsatinn.dbscheduler.console.web.Fmt.duration(h.durationMs())}</td>
                <td>${h.pickedBy()}</td>
                <td>
                    @if(taskData.visible())
                        <details data-payload-url="${ctx.basePath()}/payload/history?id=${h.id()}"><summary>Show payload</summary><pre>Loads when opened</pre></details>
                    @endif
                </td>
                <td>
                    @if(h.stacktrace() != null)
                        <details class="stack"><summary>${h.exceptionMessage()}</summary><pre>${h.stacktrace()}</pre></details>
                    @elseif(h.exceptionMessage() != null)
                        ${h.exceptionMessage()}
                    @endif
                </td>
                @if(!ctx.readOnly())
                    <td>@template.fragments.retryAction(ctx = ctx, entry = h, option = retries.get(h.id()))</td>
                @endif
            </tr>
        @endfor
        </tbody>
    </table>
    <div class="pagination">
        @if(page.previousCursor() != null)
            <a href="${ctx.basePath() + "/history" + queryBase}&cursor=${Fmt.url(page.previousCursor())}">‹ Previous</a>
        @endif
        <span>Showing ${page.items().size()}</span>
        @if(page.nextCursor() != null)
            <a href="${ctx.basePath() + "/history" + queryBase}&cursor=${Fmt.url(page.nextCursor())}">Next ›</a>
        @endif
    </div>
</div>
