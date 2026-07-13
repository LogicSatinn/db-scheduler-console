@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param String dialectName
@param String script
@template.layout(ctx = ctx, title = "History setup", content = @`
    <h1>Execution history</h1>
    <div class="card">
        <h2>Setup required</h2>
        <p>The history table has not been created yet. Live views work without it; history,
           the 24h stats, and the throughput chart need it.</p>
        <p>Detected database: <strong>${dialectName}</strong>. Apply this migration with your
           usual tool (Flyway, Liquibase, or manually):</p>
        <pre class="data">${script}</pre>
    </div>
`)
