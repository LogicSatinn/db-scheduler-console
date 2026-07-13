package io.github.logicsatinn.dbscheduler.console.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.logicsatinn.dbscheduler.console.ConsoleAvailability;
import io.github.logicsatinn.dbscheduler.console.data.Dialect;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ConsoleAvailabilityInterceptorTest {

    @Test
    void availablePassesThrough() throws Exception {
        var interceptor = new ConsoleAvailabilityInterceptor(new ConsoleAvailability(Dialect.POSTGRES));
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
                .isTrue();
    }

    @Test
    void unavailableReturns503() throws Exception {
        var interceptor = new ConsoleAvailabilityInterceptor(new ConsoleAvailability(null));
        var response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("unsupported database");
    }
}
