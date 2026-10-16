@import io.github.logicsatinn.dbscheduler.console.web.Fmt
@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param java.util.List<io.github.logicsatinn.dbscheduler.console.service.RecurringTasksService.Row> rows
@template.layout(ctx = ctx, title = "Recurring tasks", content = @`
    <h1>Task definitions</h1>
    <div class="card" id="recurring-region"
         hx-get="${ctx.basePath()}/recurring" hx-trigger="every ${ctx.pollSeconds()}s, dsc-refresh from:body"
         hx-select="#recurring-region" hx-swap="outerHTML" hx-disinherit="hx-select">
        @if(rows.isEmpty())
            <div class="empty">No task beans found in the application context.</div>
        @else
            <table class="data">
                <thead><tr><th>Task</th><th>Type</th><th>Next run</th><th>Last outcome</th><th></th></tr></thead>
                <tbody>
                @for(var row : rows)
                    <tr>
                        <td>${row.taskName()}</td>
                        <td>${row.type()}</td>
                        <td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(row.nextExecution())}</td>
                        <td>
                            @if(row.lastRun() != null)
                                <span class="state ${row.lastRun().outcome().name().toLowerCase()}">${row.lastRun().outcome().name()}</span>
                                <span class="muted">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(row.lastRun().startedAt())} · ${io.github.logicsatinn.dbscheduler.console.web.Fmt.duration(row.lastRun().durationMs())}</span>
                            @else
                                <span class="muted">—</span>
                            @endif
                        </td>
                        <td>
                            @if(!ctx.readOnly() && row.runNowInstance() != null)
                                <form style="display:inline" hx-post="${ctx.basePath()}/actions/run-now" hx-target="#flash" hx-swap="innerHTML">
                                    <input type="hidden" name="task" value="${row.taskName()}">
                                    <input type="hidden" name="id" value="${row.runNowInstance()}">
                                    <button type="submit">Run now</button>
                                </form>
                            @endif
                            <a href="${ctx.basePath()}/history?task=${Fmt.url(row.taskName())}">history</a>
                        </td>
                    </tr>
                @endfor
                </tbody>
            </table>
        @endif
    </div>
`)
