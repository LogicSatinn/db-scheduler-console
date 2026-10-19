package io.github.logicsatinn.dbscheduler.console.web;

import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import org.springframework.web.servlet.HandlerInterceptor;

/** Defense in depth only: the host must enforce authentication, authorization and CSRF. */
public final class ConsoleRequestInterceptor implements HandlerInterceptor {
    private final ConsoleProperties props;
    public ConsoleRequestInterceptor(ConsoleProperties props) { this.props = props; }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!response.containsHeader("X-Content-Type-Options")) response.setHeader("X-Content-Type-Options", "nosniff");
        if (!props.isAllowFraming()) {
            if (!response.containsHeader("X-Frame-Options")) response.setHeader("X-Frame-Options", "DENY");
            // An additional CSP policy intersects with, rather than replaces, host policies.
            response.addHeader("Content-Security-Policy", "frame-ancestors 'none'");
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith(props.getBasePath() + "/static/")) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
        }
        if (request.getMethod().equals("POST")) {
            String origin = request.getHeader("Origin");
            String expected = props.getExternalOrigin() != null ? props.getExternalOrigin()
                    : request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
            if (!"true".equals(request.getHeader("HX-Request"))
                    || "cross-site".equals(request.getHeader("Sec-Fetch-Site"))
                    || origin != null && !sameOrigin(origin, expected)) {
                response.setStatus(403);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("Console mutation request rejected");
                return false;
            }
        }
        return true;
    }
    private static boolean sameOrigin(String supplied, String expected) {
        try {
            URI a = URI.create(supplied), b = URI.create(expected);
            return a.getHost() != null && b.getHost() != null && a.getUserInfo() == null
                    && a.getQuery() == null && a.getFragment() == null
                    && (a.getPath() == null || a.getPath().isEmpty())
                    && a.getScheme().equalsIgnoreCase(b.getScheme())
                    && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b);
        } catch (IllegalArgumentException e) { return false; }
    }
    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
