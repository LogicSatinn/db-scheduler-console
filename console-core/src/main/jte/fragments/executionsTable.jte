@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param io.github.logicsatinn.dbscheduler.console.data.Page<io.github.logicsatinn.dbscheduler.console.data.ExecutionRow> page
@param io.github.logicsatinn.dbscheduler.console.data.ExecutionFilter filter
@param java.time.Instant now
@param String queryBase
@param String refreshUrl
!{var base = ctx.basePath();}
!{var sortDir = filter.descending() ? "asc" : "desc";}
<div id="executions-region"
     hx-get="${refreshUrl}" hx-trigger="dsc-refresh from:body"
     hx-select="#executions-region" hx-swap="outerHTML">
    @if(!ctx.readOnly())
        <form id="bulk-form" hx-post="${ctx.basePath()}/actions/bulk" hx-target="#flash" hx-swap="innerHTML">
            <div class="toolbar">
                <select name="kind">
                    <option value="RUN_NOW">Run now</option>
                    <option value="DELETE">Delete</option>
                </select>
                <button type="submit">Apply to selected</button>
            </div>
        </form>
    @endif
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
            <th></th>
        </tr>
        </thead>
        <tbody>
        @if(page.items().isEmpty())
            <tr><td colspan="9" class="empty">No executions match this filter.</td></tr>
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
                <td>
                    @if(!ctx.readOnly())
                        <form style="display:inline" hx-post="${ctx.basePath()}/actions/run-now" hx-target="#flash" hx-swap="innerHTML">
                            <input type="hidden" name="task" value="${row.taskName()}">
                            <input type="hidden" name="id" value="${row.instanceId()}">
                            <button type="submit">Run now</button>
                        </form>
                        <form style="display:inline" hx-post="${ctx.basePath()}/actions/delete" hx-confirm="Delete this execution?" hx-target="#flash" hx-swap="innerHTML">
                            <input type="hidden" name="task" value="${row.taskName()}">
                            <input type="hidden" name="id" value="${row.instanceId()}">
                            <button type="submit" class="danger">Delete</button>
                        </form>
                    @endif
                </td>
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
