@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param io.github.logicsatinn.dbscheduler.console.service.StatsService.Tiles tiles
@param boolean historyAvailable
@param io.github.logicsatinn.dbscheduler.console.web.ChartVm.Model chart
@param java.util.List<io.github.logicsatinn.dbscheduler.console.data.history.HistoryEntry> failures
@template.layout(ctx = ctx, title = "Overview", content = @`
    <h1>Overview</h1>
    <div hx-get="${ctx.basePath()}/fragments/overview-tiles" hx-trigger="every ${ctx.pollSeconds()}s" hx-swap="innerHTML">
        @template.fragments.tiles(tiles = tiles, historyAvailable = historyAvailable)
    </div>
    <div class="card">
        <h2>Throughput · last 24 h</h2>
        <div hx-get="${ctx.basePath()}/fragments/overview-chart" hx-trigger="every ${ctx.pollSeconds()}s" hx-swap="innerHTML">
            @template.fragments.chart(chart = chart)
        </div>
    </div>
    <div class="card">
        <h2>Recent failures</h2>
        <div hx-get="${ctx.basePath()}/fragments/overview-recent" hx-trigger="every ${ctx.pollSeconds()}s" hx-swap="innerHTML">
            @template.fragments.recentFailures(failures = failures, basePath = ctx.basePath())
        </div>
    </div>
`)
