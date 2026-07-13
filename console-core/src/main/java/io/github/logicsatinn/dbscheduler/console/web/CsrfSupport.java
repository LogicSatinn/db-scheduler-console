package io.github.logicsatinn.dbscheduler.console.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.ClassUtils;

/** Emits htmx hx-headers JSON when Spring Security CSRF is active. Safe without spring-security-web. */
public final class CsrfSupport {

    private static final boolean SPRING_SECURITY_PRESENT = ClassUtils.isPresent(
            "org.springframework.security.web.csrf.CsrfToken", CsrfSupport.class.getClassLoader());

    private CsrfSupport() {}

    public static String headerJson(HttpServletRequest request) {
        if (!SPRING_SECURITY_PRESENT || request == null) {
            return null;
        }
        return Holder.json(request);
    }

    /** Separate class so CsrfToken is only loaded when spring-security-web is on the classpath. */
    private static final class Holder {
        static String json(HttpServletRequest request) {
            Object attr = request.getAttribute(
                    org.springframework.security.web.csrf.CsrfToken.class.getName());
            if (attr instanceof org.springframework.security.web.csrf.CsrfToken token
                    && token.getToken() != null) {
                return "{\"" + token.getHeaderName() + "\":\"" + token.getToken() + "\"}";
            }
            return null;
        }
    }
}
