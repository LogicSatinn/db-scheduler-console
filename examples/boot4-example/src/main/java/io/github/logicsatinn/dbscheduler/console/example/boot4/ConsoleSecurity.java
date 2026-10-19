package io.github.logicsatinn.dbscheduler.console.example.boot4;

import org.springframework.security.web.AuthenticationEntryPoint;
import io.github.logicsatinn.dbscheduler.console.ConsoleProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class ConsoleSecurity {

    @Bean
    @Order(1)
    SecurityFilterChain consoleSecurityFilterChain(HttpSecurity http,
            ConsoleProperties props) throws Exception {
        return http.securityMatcher(props.getBasePath(), props.getBasePath() + "/**")
                .authorizeHttpRequests(requests -> requests.anyRequest().hasRole("SCHEDULER_ADMIN"))
                .httpBasic(basic -> basic.authenticationEntryPoint(unauthorized()))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized()))
                .build();
    }

    private static AuthenticationEntryPoint unauthorized() {
        return (request, response, error) -> {
            response.setHeader("WWW-Authenticate", "Basic realm=\"db-scheduler-console\"");
            response.setStatus(401);
        };
    }

    @Bean
    @Order(2)
    SecurityFilterChain fallbackSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(requests -> requests.anyRequest().denyAll()).build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService consoleAdministrators(
            @Value("${demo.console.username}") String username,
            @Value("${demo.console.password}") String password,
            PasswordEncoder encoder,
            @Value("${server.address:127.0.0.1}") String bindAddress) {
        boolean loopback = bindAddress.equals("127.0.0.1") || bindAddress.equals("::1");
        if (!loopback && (username.isBlank() || password.isBlank() || password.equals("demo-password")))
            throw new IllegalStateException("Nonlocal examples require deployment-provided console credentials");
        if (username.isBlank() || password.isBlank())
            throw new IllegalStateException("Set CONSOLE_USERNAME and CONSOLE_PASSWORD before starting the example");
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(encoder.encode(password))
                .roles("SCHEDULER_ADMIN")
                .build());
    }
}
