package io.github.logicsatinn.dbscheduler.console.web;

import io.github.logicsatinn.dbscheduler.console.ConsoleAvailability;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public class ConsoleAvailabilityInterceptor implements HandlerInterceptor {

    private final ConsoleAvailability availability;

    public ConsoleAvailabilityInterceptor(ConsoleAvailability availability) {
        this.availability = availability;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (availability.available()) {
            return true;
        }
        response.setStatus(503);
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write(
                "<p>db-scheduler-console is disabled: unsupported database. See the application log.</p>");
        return false;
    }
}
