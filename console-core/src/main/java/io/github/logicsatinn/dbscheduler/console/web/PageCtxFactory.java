package io.github.logicsatinn.dbscheduler.console.web;

import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import jakarta.servlet.http.HttpServletRequest;

public class PageCtxFactory {

    private final ConsoleProperties props;

    public PageCtxFactory(ConsoleProperties props) {
        this.props = props;
    }

    public PageCtx page(String active, HttpServletRequest request) {
        return new PageCtx(
                (request == null ? "" : request.getContextPath()) + props.getBasePath(),
                props.isReadOnly(),
                Math.max(1, props.getPollingInterval().toSeconds()),
                CsrfSupport.headerJson(request),
                active,
                props.getTitle() == null || props.getTitle().isBlank()
                        ? "db-scheduler-console" : props.getTitle().strip());
    }
}
