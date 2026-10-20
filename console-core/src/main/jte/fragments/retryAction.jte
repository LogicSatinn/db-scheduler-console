@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry entry
@param io.github.logicsatinn.dbscheduler.console.service.RetryLookup.Option option
@if(option == io.github.logicsatinn.dbscheduler.console.service.RetryLookup.Option.RETRY)
    <form class="inline-form" hx-post="${ctx.basePath()}/actions/retry" hx-target="#flash" hx-swap="innerHTML">
        <input type="hidden" name="task" value="${entry.taskName()}">
        <input type="hidden" name="id" value="${entry.instanceId()}">
        <button type="submit">Retry</button>
    </form>
@elseif(option == io.github.logicsatinn.dbscheduler.console.service.RetryLookup.Option.RERUN)
    <form class="inline-form" hx-post="${ctx.basePath()}/actions/rerun"
          hx-confirm="Run ${entry.instanceId()} again with the payload recorded for this failed run?"
          hx-target="#flash" hx-swap="innerHTML">
        <input type="hidden" name="history" value="${entry.id()}">
        <button type="submit">Re-run</button>
    </form>
@endif
