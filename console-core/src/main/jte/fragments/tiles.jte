@param io.github.logicsatinn.dbscheduler.console.service.StatsService.Tiles tiles
@param boolean historyAvailable
<div class="tiles">
    <div class="tile"><div class="label">Scheduled</div><div class="value">${tiles.scheduled()}</div></div>
    <div class="tile"><div class="label">Due</div><div class="value">${tiles.due()}</div></div>
    <div class="tile"><div class="label">Running</div><div class="value">${tiles.running()}</div></div>
    <div class="tile @if(tiles.retrying() > 0)alert@endif"><div class="label">Retrying</div><div class="value">${tiles.retrying()}</div></div>
    <div class="tile @if(tiles.failed() > 0)alert@endif"><div class="label">Failed</div><div class="value">${tiles.failed()}</div></div>
    @if(historyAvailable)
        <div class="tile"><div class="label">Succeeded · 24h</div><div class="value">${tiles.succeeded24h()}</div></div>
        <div class="tile"><div class="label">Failed · 24h</div><div class="value">${tiles.failed24h()}</div></div>
    @endif
</div>
