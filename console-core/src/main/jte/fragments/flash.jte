@param io.github.logicsatinn.dbscheduler.console.service.ExecutionActions.ActionResult result
<div class="flash @if(!result.success())error@endif" data-dismiss-flash role="status">
    ${result.message()} <span class="muted">· click to dismiss</span>
</div>
