@import io.github.logicsatinn.dbscheduler.console.web.Fmt
@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param io.github.logicsatinn.dbscheduler.console.data.Page<io.github.logicsatinn.dbscheduler.console.data.ExecutionRow> page
@param io.github.logicsatinn.dbscheduler.console.data.ExecutionFilter filter
@param java.time.Instant now
@param String queryBase
@param String refreshUrl
!{var base = ctx.basePath();}
!{var sortDir = filter.descending() ? "asc" : "desc";}
<div id="executions-region"
     hx-get="${refreshUrl}"
     hx-trigger="every ${ctx.pollSeconds()}s, dsc-refresh from:body"
     hx-select="#executions-region" hx-swap="outerHTML">
    @if(!ctx.readOnly())
        <form id="bulk-form" hx-post="${ctx.basePath()}/actions/bulk" hx-target="#flash" hx-swap="innerHTML">
            <div class="toolbar">
                <label for="bulk-kind" class="muted">With selected</label>
                <select name="kind" id="bulk-kind">
                    <option value="RUN_NOW">Run now</option>
                    <option value="DELETE">Delete</option>
                </select>
                <button type="submit">Apply</button>
            </div>
        </form>
    @endif
    <table class="data">
        <thead>
        <tr>
            <th></th>
            <th><a href="${base}/executions${queryBase}&sort=TASK_NAME&dir=${filter.sort().name().equals("TASK_NAME") ? sortDir : "asc"}">Task</a></th>
            <th>Instance ID</th>
            <th>State</th>
            <th><a href="${base}/executions${queryBase}&sort=EXECUTION_TIME&dir=${filter.sort().name().equals("EXECUTION_TIME") ? sortDir : "asc"}">Scheduled for</a></th>
            <th title="Consecutive failed attempts"><a href="${base}/executions${queryBase}&sort=CONSECUTIVE_FAILURES&dir=${filter.sort().name().equals("CONSECUTIVE_FAILURES") ? sortDir : "desc"}">Failures</a></th>
            <th>Node</th>
            <th><a href="${base}/executions${queryBase}&sort=LAST_HEARTBEAT&dir=${filter.sort().name().equals("LAST_HEARTBEAT") ? sortDir : "asc"}">Last heartbeat</a></th>
            <th></th>
        </tr>
        </thead>
        <tbody>
        @if(page.items().isEmpty())
            <tr><td colspan="9" class="empty">No executions match these filters.</td></tr>
        @endif
        @for(var row : page.items())
            !{var state = row.state(now);}
            <tr>
                <td><input type="checkbox" name="ref" value="${row.taskName()}::${row.instanceId()}" form="bulk-form"></td>
                <td>${row.taskName()}</td>
                <td><a href="${base}/execution?task=${Fmt.url(row.taskName())}&id=${Fmt.url(row.instanceId())}">${row.instanceId()}</a></td>
                <td><span class="state ${state.name().toLowerCase()}">${Fmt.state(state)}</span></td>
                <td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(row.executionTime())}</td>
                <td class="num">${row.consecutiveFailures()}</td>
                <td>${row.pickedBy()}</td>
                <td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(row.lastHeartbeat())}</td>
                <td>
                    @if(!ctx.readOnly())
                        <form class="inline-form" hx-post="${ctx.basePath()}/actions/run-now" hx-target="#flash" hx-swap="innerHTML">
                            <input type="hidden" name="task" value="${row.taskName()}">
                            <input type="hidden" name="id" value="${row.instanceId()}">
                            <button type="submit">${row.parked() ? "Requeue" : "Run now"}</button>
                        </form>
                        <form class="inline-form" hx-post="${ctx.basePath()}/actions/delete" hx-confirm="Delete this execution?" hx-target="#flash" hx-swap="innerHTML">
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
        @if(page.previousCursor() != null)
            <a href="${base + "/executions" + queryBase + "&sort=" + filter.sort().name() + "&dir=" + (filter.descending() ? "desc" : "asc")}&cursor=${Fmt.url(page.previousCursor())}">‹ Previous</a>
        @endif
        <span>Showing ${page.items().size()}</span>
        @if(page.nextCursor() != null)
            <a href="${base + "/executions" + queryBase + "&sort=" + filter.sort().name() + "&dir=" + (filter.descending() ? "desc" : "asc")}&cursor=${Fmt.url(page.nextCursor())}">Next ›</a>
        @endif
    </div>
</div>
