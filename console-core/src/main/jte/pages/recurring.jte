@import io.github.logicsatinn.dbscheduler.console.web.Fmt
@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param java.util.List<io.github.logicsatinn.dbscheduler.console.service.RecurringTasksService.Row> rows
!{boolean selectable = !ctx.readOnly() && rows.stream().anyMatch(r -> r.runNowInstance() != null);}
@template.layout(ctx = ctx, title = "Tasks", content = @`
    <h1>Tasks</h1>
    <div class="card" id="recurring-region"
         hx-get="${ctx.basePath()}/recurring" hx-trigger="every ${ctx.pollSeconds()}s, dsc-refresh from:body"
         hx-select="#recurring-region" hx-swap="outerHTML">
        @if(rows.isEmpty())
            <div class="empty">No tasks are registered with the scheduler.</div>
        @else
            @if(selectable)
                <form id="tasks-bulk-form" hx-post="${ctx.basePath()}/actions/bulk" hx-target="#flash" hx-swap="innerHTML">
                    <input type="hidden" name="kind" value="RUN_NOW">
                    <div class="toolbar">
                        <button type="submit">Run selected now</button>
                    </div>
                </form>
            @endif
            <table class="data">
                <thead><tr>@if(selectable)<th></th>@endif<th>Task</th><th>Type</th><th>Next run</th><th>Last run</th><th></th></tr></thead>
                <tbody>
                @for(var row : rows)
                    <tr>
                        @if(selectable)
                            <td>
                                @if(row.runNowInstance() != null)
                                    <input type="checkbox" name="ref" value="${row.taskName()}::${row.runNowInstance()}" form="tasks-bulk-form" aria-label="Select ${row.taskName()}">
                                @endif
                            </td>
                        @endif
                        <td class="task">${row.taskName()}</td>
                        <td>${row.type()}</td>
                        <td class="time">${Fmt.ts(row.nextExecution())}</td>
                        <td>
                            @if(row.lastRun() != null)
                                <span class="state ${row.lastRun().outcome().name().toLowerCase()}">${Fmt.outcome(row.lastRun().outcome())}</span>
                                <span class="muted">${Fmt.ts(row.lastRun().startedAt())} · ${Fmt.duration(row.lastRun().durationMs())}</span>
                            @else
                                <span class="muted">—</span>
                            @endif
                        </td>
                        <td>
                            @if(!ctx.readOnly() && row.runNowInstance() != null)
                                <form class="inline-form" hx-post="${ctx.basePath()}/actions/run-now" hx-target="#flash" hx-swap="innerHTML">
                                    <input type="hidden" name="task" value="${row.taskName()}">
                                    <input type="hidden" name="id" value="${row.runNowInstance()}">
                                    <button type="submit">Run now</button>
                                </form>
                            @endif
                            <a href="${ctx.basePath()}/history?task=${Fmt.url(row.taskName())}">View history</a>
                        </td>
                    </tr>
                @endfor
                </tbody>
            </table>
        @endif
    </div>
`)
