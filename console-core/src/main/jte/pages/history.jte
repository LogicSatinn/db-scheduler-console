@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param io.github.logicsatinn.dbscheduler.console.data.history.HistoryFilter filter
@param String outcomeParam
@param String fromParam
@param String toParam
@param io.github.logicsatinn.dbscheduler.console.data.Page<io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry> page
@param io.github.logicsatinn.dbscheduler.console.service.TaskDataRenderer taskData
@param boolean legacy
@param String upgradeScript
@param String queryBase
@param java.util.Map<Long, io.github.logicsatinn.dbscheduler.console.service.RetryLookup.Option> retries = java.util.Map.of()
@template.layout(ctx = ctx, title = "History", content = @`
    <h1>History</h1>
    @if(legacy)
        <div class="card">
            <h2>Upgrade required</h2>
            <p>This M1 schema continues to record and display metadata. Apply the M2 migration
               to retain payloads and enable failed-execution parking:</p>
            <pre class="data">${upgradeScript}</pre>
        </div>
    @endif
    <form class="filters"
          hx-get="${ctx.basePath()}/history"
          hx-target="#history-region" hx-select="#history-region" hx-swap="outerHTML"
          hx-push-url="true"
          hx-trigger="change, submit, input delay:500ms from:'find input[name=q]'">
        <label>Task
            <input type="search" name="task" value="${filter.taskName()}" placeholder="Task name">
        </label>
        <label>Outcome
            <select name="outcome">
                <option value="">All</option>
                <option value="SUCCEEDED" selected="${outcomeParam.equalsIgnoreCase("SUCCEEDED")}">Succeeded</option>
                <option value="FAILED" selected="${outcomeParam.equalsIgnoreCase("FAILED")}">Failed</option>
            </select>
        </label>
        <label>Search
            <input type="search" name="q" value="${filter.textSearch()}" placeholder="Instance ID or error message">
        </label>
        <label>Started from <input type="datetime-local" name="from" value="${fromParam}"></label>
        <label>Started until <input type="datetime-local" name="to" value="${toParam}"></label>
        <input type="hidden" name="size" value="${filter.pageSize()}">
        <button type="submit">Apply</button>
    </form>
    @template.fragments.historyTable(ctx = ctx, page = page, filter = filter, queryBase = queryBase, taskData = taskData, retries = retries)
`)
