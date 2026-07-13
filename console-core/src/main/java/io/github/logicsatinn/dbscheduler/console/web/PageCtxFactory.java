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
                props.getBasePath(),
                props.isReadOnly(),
                Math.max(1, props.getPollingInterval().toSeconds()),
                CsrfSupport.headerJson(request),
                active);
    }
}
