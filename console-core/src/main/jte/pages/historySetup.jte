@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param String dialectName
@param boolean legacy
@param String script
@template.layout(ctx = ctx, title = "History setup", content = @`
    <h1>Execution history</h1>
    <div class="card">
        <h2>${legacy ? "Upgrade required" : "Setup required"}</h2>
        @if(legacy)
            <p>The M1 history table is still recording metadata, but payload history and failed
               execution parking require the M2 upgrade. Apply this script before using those features.</p>
        @else
            <p>The history table has not been created yet. Live views work without it; history,
               the 24h stats, and the throughput chart need it.</p>
        @endif
        <p>Detected database: <strong>${dialectName}</strong>. Apply this migration with your
           usual tool (Flyway, Liquibase, or manually):</p>
        <pre class="data">${script}</pre>
    </div>
`)
