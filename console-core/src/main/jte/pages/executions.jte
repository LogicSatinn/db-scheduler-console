@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param io.github.logicsatinn.dbscheduler.console.data.ExecutionFilter filter
@param String stateParam
@param String fromParam
@param String toParam
@param io.github.logicsatinn.dbscheduler.console.data.Page<io.github.logicsatinn.dbscheduler.console.data.ExecutionRow> page
@param java.util.List<String> taskNames
@param java.time.Instant now
@param String queryBase
@param String refreshUrl
@template.layout(ctx = ctx, title = "Executions", content = @`
    <h1>Executions</h1>
    <form class="filters"
          hx-get="${ctx.basePath()}/executions"
          hx-target="#executions-region" hx-select="#executions-region" hx-swap="outerHTML"
          hx-push-url="true"
          hx-trigger="change, submit, input delay:500ms from:find input[name='q']">
        <label>State
            <select name="state">
                <option value="">All</option>
                @for(var s : io.github.logicsatinn.dbscheduler.console.data.ExecutionState.values())
                    <option value="${s.name()}" selected="${s.name().equals(stateParam)}">${s.name()}</option>
                @endfor
            </select>
        </label>
        <label>Task
            <select name="task">
                <option value="">All</option>
                @for(var name : taskNames)
                    <option value="${name}" selected="${name.equals(filter.taskName())}">${name}</option>
                @endfor
            </select>
        </label>
        <label>Instance contains
            <input type="search" name="q" value="${filter.instanceContains()}" placeholder="instance id">
        </label>
        <label>From
            <input type="datetime-local" name="from" value="${fromParam}">
        </label>
        <label>To
            <input type="datetime-local" name="to" value="${toParam}">
        </label>
        <input type="hidden" name="size" value="${filter.pageSize()}">
        <button type="submit">Apply</button>
    </form>
    @template.fragments.executionsTable(ctx = ctx, page = page, filter = filter, now = now, queryBase = queryBase, refreshUrl = refreshUrl)
`)
