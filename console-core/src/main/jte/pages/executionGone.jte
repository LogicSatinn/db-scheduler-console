@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param String taskName
@param String instanceId
@param java.util.List<io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry> historyEntries
@template.layout(ctx = ctx, title = taskName + " / " + instanceId, content = @`
    <h1>${taskName} <span class="muted">/</span> ${instanceId}</h1>
    <%-- Same id as the live detail region so a polling detail page swaps this in; no hx-trigger, so polling stops. --%>
    <div id="detail-live">
    @if(historyEntries.isEmpty())
        <div class="card">
            <div class="empty">Execution not found: it is no longer scheduled and has no recorded history.</div>
        </div>
    @else
        !{var last = historyEntries.get(0);}
        <div class="card">
            <h2>Finished</h2>
            <p>No longer scheduled. Last outcome
                <span class="state ${last.outcome().name().toLowerCase()}">${last.outcome().name()}</span>
                <span class="muted">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(last.finishedAt())}</span></p>
        </div>
        @template.fragments.instanceHistory(historyEntries = historyEntries)
    @endif
    </div>
`)
