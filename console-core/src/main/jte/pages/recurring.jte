@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param java.util.List<io.github.logicsatinn.dbscheduler.console.service.RecurringTasksService.Row> rows
@template.layout(ctx = ctx, title = "Recurring tasks", content = @`
    <h1>Task definitions</h1>
    <div class="card">
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
                        <td><a href="${ctx.basePath()}/history?task=${row.taskName()}">history</a></td>
                    </tr>
                @endfor
                </tbody>
            </table>
        @endif
    </div>
`)
