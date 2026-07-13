@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param io.github.logicsatinn.dbscheduler.console.data.Page<io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry> page
@param io.github.logicsatinn.dbscheduler.console.data.history.HistoryFilter filter
@param String queryBase
<div id="history-region">
    <table class="data">
        <thead><tr><th>Task</th><th>Instance</th><th>Outcome</th><th>Started</th><th>Duration</th><th>Node</th><th>Error</th></tr></thead>
        <tbody>
        @if(page.items().isEmpty())
            <tr><td colspan="7" class="empty">No history entries match this filter.</td></tr>
        @endif
        @for(var h : page.items())
            <tr>
                <td>${h.taskName()}</td>
                <td><a href="${ctx.basePath()}/execution?task=${h.taskName()}&id=${h.instanceId()}">${h.instanceId()}</a></td>
                <td><span class="state ${h.outcome().name().toLowerCase()}">${h.outcome().name()}</span></td>
                <td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(h.startedAt())}</td>
                <td class="num">${io.github.logicsatinn.dbscheduler.console.web.Fmt.duration(h.durationMs())}</td>
                <td>${h.pickedBy()}</td>
                <td>
                    @if(h.stacktrace() != null)
                        <details class="stack"><summary>${h.exceptionMessage()}</summary><pre>${h.stacktrace()}</pre></details>
                    @elseif(h.exceptionMessage() != null)
                        ${h.exceptionMessage()}
                    @endif
                </td>
            </tr>
        @endfor
        </tbody>
    </table>
    <div class="pagination">
        @if(filter.page() > 0)
            <a href="${ctx.basePath()}/history${queryBase}&page=${filter.page() - 1}">‹ Prev</a>
        @endif
        <span>Page ${filter.page() + 1} of ${Math.max(1, page.totalPages())} · ${page.total()} total</span>
        @if(filter.page() + 1 < page.totalPages())
            <a href="${ctx.basePath()}/history${queryBase}&page=${filter.page() + 1}">Next ›</a>
        @endif
    </div>
</div>
