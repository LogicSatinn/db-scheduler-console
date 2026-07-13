@param io.github.logicsatinn.dbscheduler.console.web.ChartVm.Model chart
@if(chart.empty())
    <div class="empty">No execution history yet — history powers this chart.</div>
@else
    <svg class="chart-svg" viewBox="0 0 720 170" role="img" aria-label="Executions per hour, last 24 hours">
        @for(var g : chart.gridLines())
            <line class="chart-grid" x1="30" x2="715" y1="${g.y()}" y2="${g.y()}"/>
            <text class="chart-label" x="26" y="${g.y() + 3}" text-anchor="end">${g.label()}</text>
        @endfor
        @for(var bar : chart.bars())
            <g>
                <title>${bar.tooltip()}</title>
                @if(bar.heightSucceeded() > 0)
                    <rect class="viz-good" x="${bar.x()}" y="${bar.ySucceeded()}" width="${bar.width()}" height="${bar.heightSucceeded()}" rx="2"/>
                @endif
                @if(bar.heightFailed() > 0)
                    <rect class="viz-critical" x="${bar.x()}" y="${bar.yFailed()}" width="${bar.width()}" height="${bar.heightFailed()}" rx="2"/>
                @endif
            </g>
        @endfor
        @for(var label : chart.xLabels())
            <text class="chart-label" x="${label.x()}" y="156" text-anchor="middle">${label.text()}</text>
        @endfor
    </svg>
    <div class="legend">
        <span><span class="key good"></span>Succeeded</span>
        <span><span class="key critical"></span>Failed</span>
    </div>
@endif
