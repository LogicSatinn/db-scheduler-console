@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param io.github.logicsatinn.dbscheduler.console.data.ExecutionRow row
@param java.time.Instant now
@param boolean payloadVisible
@param java.util.List<io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry> historyEntries
@param String selfUrl
@template.layout(ctx = ctx, title = row.taskName() + " / " + row.instanceId(), content = @`
    <h1>${row.taskName()} <span class="muted">/</span> ${row.instanceId()}</h1>
    <div id="detail-live" hx-get="${selfUrl}"
         hx-trigger="every ${ctx.pollSeconds()}s, dsc-refresh from:body"
         hx-select="#detail-live" hx-swap="outerHTML">
        <div class="card">
            <h2>Execution</h2>
            <table class="data">
                <tbody>
                <tr><th>State</th><td><span class="state ${row.state(now).name().toLowerCase()}">${io.github.logicsatinn.dbscheduler.console.web.Fmt.state(row.state(now))}</span></td></tr>
                <tr><th>Scheduled for</th><td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(row.executionTime())}</td></tr>
                <tr><th>Node</th><td>${row.pickedBy()}</td></tr>
                <tr><th>Consecutive failures</th><td class="num">${row.consecutiveFailures()}</td></tr>
                <tr><th>Last success</th><td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(row.lastSuccess())}</td></tr>
                <tr><th>Last failure</th><td class="time">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(row.lastFailure())}</td></tr>
                <tr><th>Row version</th><td class="num">${row.version()}</td></tr>
                <tr><th>Priority</th><td class="num">${row.priority()}</td></tr>
                </tbody>
            </table>
        </div>
        <div class="card">
            <h2>Payload</h2>
            @if(payloadVisible)
                <details data-payload-url="${ctx.basePath()}/payload/execution?task=${io.github.logicsatinn.dbscheduler.console.web.Fmt.url(row.taskName())}&id=${io.github.logicsatinn.dbscheduler.console.web.Fmt.url(row.instanceId())}"><summary>Show payload</summary><pre>Loads when opened</pre></details>
            @else
                <p class="muted">Payload display is turned off. Set <code>db-scheduler-console.task-data.visible=true</code> to show it.</p>
            @endif
        </div>
        @if(row.parked() && row.exceptionClass() != null)
            <div class="card">
                <h2>Final error</h2>
                <p><strong>${row.exceptionClass()}</strong>: ${row.exceptionMessage()}</p>
                @if(row.stacktrace() != null)
                    <pre class="data">${row.stacktrace()}</pre>
                @endif
            </div>
        @endif
        @if(!ctx.readOnly())
            <div class="card">
                <h2>Actions</h2>
                !{var state = row.state(now);}
                !{boolean failed = state == io.github.logicsatinn.dbscheduler.console.data.ExecutionState.RETRYING
                        || state == io.github.logicsatinn.dbscheduler.console.data.ExecutionState.FAILED;}
                <form class="inline-form" hx-post="${ctx.basePath()}/actions/${failed ? "retry" : "run-now"}" hx-target="#flash" hx-swap="innerHTML">
                    <input type="hidden" name="task" value="${row.taskName()}">
                    <input type="hidden" name="id" value="${row.instanceId()}">
                    <button type="submit">${failed ? "Retry" : "Run now"}</button>
                </form>
                @if(!row.parked())
                    <form class="inline-form" hx-post="${ctx.basePath()}/actions/reschedule" hx-target="#flash" hx-swap="innerHTML">
                        <input type="hidden" name="task" value="${row.taskName()}">
                        <input type="hidden" name="id" value="${row.instanceId()}">
                        <input type="datetime-local" name="when" required>
                        <button type="submit">Reschedule</button>
                    </form>
                @endif
                <form class="inline-form" hx-post="${ctx.basePath()}/actions/delete" hx-confirm="Delete this execution?" hx-target="#flash" hx-swap="innerHTML">
                    <input type="hidden" name="task" value="${row.taskName()}">
                    <input type="hidden" name="id" value="${row.instanceId()}">
                    <button type="submit" class="danger">Delete</button>
                </form>
            </div>
        @endif
        @template.fragments.instanceHistory(historyEntries = historyEntries)
    </div>
`)
