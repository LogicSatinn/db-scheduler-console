@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param io.github.logicsatinn.dbscheduler.console.data.Page<io.github.logicsatinn.dbscheduler.console.data.ExecutionRow> page
@param io.github.logicsatinn.dbscheduler.console.data.ExecutionFilter filter
@param java.time.Instant now
@param String queryBase
!{var base = ctx.basePath();}
!{var sortDir = filter.descending() ? "asc" : "desc";}
<div id="executions-region">
    <table class="data">
        <thead>
        <tr>
            <th></th>
            <th><a href="${base}/executions${queryBase}&sort=TASK_NAME&dir=${filter.sort().name().equals("TASK_NAME") ? sortDir : "asc"}">Task</a></th>
            <th>Instance</th>
            <th>State</th>
            <th><a href="${base}/executions${queryBase}&sort=EXECUTION_TIME&dir=${filter.sort().name().equals("EXECUTION_TIME") ? sortDir : "asc"}">Execution time</a></th>
            <th><a href="${base}/executions${queryBase}&sort=CONSECUTIVE_FAILURES&dir=${filter.sort().name().equals("CONSECUTIVE_FAILURES") ? sortDir : "desc"}">Failures</a></th>
            <th>Picked by</th>
            <th><a href="${base}/executions${queryBase}&sort=LAST_HEARTBEAT&dir=${filter.sort().name().equals("LAST_HEARTBEAT") ? sortDir : "asc"}">Heartbeat</a></th>
        </tr>
        </thead>
        <tbody>
        @if(page.items().isEmpty())
            <tr><td colspan="8" class="empty">No executions match this filter.</td></tr>
        @endif
        @for(var row : page.items())
            !{var state = row.state(now);}
            <tr>
                <td><input type="checkbox" name="ref" value="${row.taskName()}::${row.instanceId()}" form="bulk-form"></td>
                <td>${row.taskName()}</td>
                <td><a href="${base}/execution?task=${row.taskName()}&id=${row.instanceId()}">${row.instanceId()}</a></td>
                <td><span class="state ${state.name().toLowerCase()}">${state.name()}</span></td>
                <td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(row.executionTime())}</td>
                <td class="num">${row.consecutiveFailures()}</td>
                <td>${row.pickedBy()}</td>
                <td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(row.lastHeartbeat())}</td>
            </tr>
        @endfor
        </tbody>
    </table>
    <div class="pagination">
        @if(filter.page() > 0)
            <a href="${base}/executions${queryBase}&sort=${filter.sort().name()}&dir=${filter.descending() ? "desc" : "asc"}&page=${filter.page() - 1}">‹ Prev</a>
        @endif
        <span>Page ${filter.page() + 1} of ${Math.max(1, page.totalPages())} · ${page.total()} total</span>
        @if(filter.page() + 1 < page.totalPages())
            <a href="${base}/executions${queryBase}&sort=${filter.sort().name()}&dir=${filter.descending() ? "desc" : "asc"}&page=${filter.page() + 1}">Next ›</a>
        @endif
    </div>
</div>
