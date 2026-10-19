package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.*;
import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ConsoleRequestInterceptorTest {
    @Test void defaultsProtectResponsesAndRetainHostCsp() throws Exception {
        var props = new ConsoleProperties();
        assertThat(props.isReadOnly()).isTrue();
        assertThat(props.getTaskData().isVisible()).isFalse();
        assertThat(props.getHistory().isStoreTaskData()).isFalse();
        var request = new MockHttpServletRequest("GET", "/db-scheduler-console/history");
        var response = new MockHttpServletResponse();
        response.setHeader("Content-Security-Policy", "default-src 'none'");
        assertThat(new ConsoleRequestInterceptor(props).preHandle(request, response, this)).isTrue();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeaders("Content-Security-Policy")).containsExactly("default-src 'none'", "frame-ancestors 'none'");
    }
    @Test void customOriginAndMissingHeadersAreHandledWithoutTrustingForwardedHeaders() throws Exception {
        var props = new ConsoleProperties(); props.setExternalOrigin("https://console.example");
        var interceptor = new ConsoleRequestInterceptor(props);
        var request = new MockHttpServletRequest("POST", "/db-scheduler-console/actions/delete");
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), this)).isFalse();
        request.addHeader("HX-Request", "true");
        request.addHeader("Origin", "https://console.example");
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), this)).isTrue();
        request.removeHeader("Origin"); request.addHeader("Origin", "null");
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), this)).isFalse();
    }
    @Test void approvedEmbeddingAndStaticCachingDoNotWeakenHostPolicy() throws Exception {
        var props = new ConsoleProperties(); props.setAllowFraming(true);
        var response = new MockHttpServletResponse(); response.setHeader("X-Frame-Options", "DENY");
        new ConsoleRequestInterceptor(props).preHandle(new MockHttpServletRequest("GET",
                "/db-scheduler-console/static/console.js"), response, this);
        assertThat(response.getHeader("Cache-Control")).isNull();
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
    }
}
