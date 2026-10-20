@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param String taskName
@param String instanceId
@param java.util.List<io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry> historyEntries
@param Long rerunId = null
@param String selfUrl = null
@template.layout(ctx = ctx, title = taskName + " / " + instanceId, content = @`
    <h1>${taskName} <span class="muted">/</span> ${instanceId}</h1>
    <div id="detail-live"
         hx-get="${rerunId == null ? null : selfUrl}"
         hx-trigger="${rerunId == null ? null : "dsc-refresh from:body"}"
         hx-select="${rerunId == null ? null : "#detail-live"}"
         hx-swap="${rerunId == null ? null : "outerHTML"}">
    @if(historyEntries.isEmpty())
        <div class="card">
            <div class="empty">Execution not found. It is no longer scheduled and has no recorded history.</div>
        </div>
    @else
        !{var last = historyEntries.get(0);}
        <div class="card">
            <h2>No longer scheduled</h2>
            <p>Last run
                <span class="state ${last.outcome().name().toLowerCase()}">${io.github.logicsatinn.dbscheduler.console.web.Fmt.outcome(last.outcome())}</span>
                <span class="muted">${io.github.logicsatinn.dbscheduler.console.web.Fmt.ts(last.finishedAt())}</span></p>
            @if(rerunId != null)
                <form class="inline-form" hx-post="${ctx.basePath()}/actions/rerun"
                      hx-confirm="Run ${instanceId} again with the payload recorded for its last failed run?"
                      hx-target="#flash" hx-swap="innerHTML">
                    <input type="hidden" name="history" value="${rerunId}">
                    <button type="submit">Re-run</button>
                </form>
            @endif
        </div>
        @template.fragments.instanceHistory(historyEntries = historyEntries)
    @endif
    </div>
`)
