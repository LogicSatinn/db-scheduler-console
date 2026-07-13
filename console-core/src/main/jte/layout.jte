@param io.github.logicsatinn.dbscheduler.console.web.PageCtx ctx
@param String title
@param gg.jte.Content content
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${title} · db-scheduler-console</title>
    <link rel="stylesheet" href="${ctx.basePath()}/static/console.css">
    <script src="${ctx.basePath()}/static/htmx.min.js" defer></script>
</head>
<body hx-headers="${ctx.csrfHeaderJson()}">
<div class="shell">
    <nav class="nav">
        <span class="brand">db-scheduler-console</span>
        <a href="${ctx.basePath()}/overview" class="@if(ctx.active().equals("overview"))active@endif">Overview</a>
        <a href="${ctx.basePath()}/executions" class="@if(ctx.active().equals("executions"))active@endif">Executions</a>
        <a href="${ctx.basePath()}/recurring" class="@if(ctx.active().equals("recurring"))active@endif">Recurring</a>
        <a href="${ctx.basePath()}/history" class="@if(ctx.active().equals("history"))active@endif">History</a>
        @if(ctx.readOnly())<span class="badge">read-only</span>@endif
    </nav>
    <main id="main">
        ${content}
    </main>
    <div id="flash" class="flash-area"></div>
</div>
</body>
</html>
