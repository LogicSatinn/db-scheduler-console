package io.github.logicsatinn.dbscheduler.console.example.boot4;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** Host-owned example security. The console starter deliberately provides none. */
@Configuration(proxyBeanMethods = false)
class ConsoleSecurity {

    @Bean
    @Order(1)
    SecurityFilterChain consoleSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/db-scheduler-console/**")
                .authorizeHttpRequests(requests -> requests.anyRequest().hasRole("SCHEDULER_ADMIN"))
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService consoleAdministrators(
            @Value("${demo.console.username}") String username,
            @Value("${demo.console.password}") String password,
            PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(encoder.encode(password))
                .roles("SCHEDULER_ADMIN")
                .build());
    }
}
