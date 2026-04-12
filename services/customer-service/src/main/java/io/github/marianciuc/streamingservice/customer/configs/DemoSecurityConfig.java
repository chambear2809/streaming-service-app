package io.github.marianciuc.streamingservice.customer.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Demo-profile security configuration — intentionally permits all requests.
 *
 * <p>This bean is only active when {@code spring.profiles.active=broadcast-demo}. All HTTP
 * security (CORS, CSRF, and authorization) is disabled so that the frontend gateway
 * in {@code frontend/server.js} can act as the sole enforcement boundary during demos.
 *
 * <p><strong>Do not enable this profile on a network-accessible deployment.</strong>
 * Replace this class with a production {@code SecurityFilterChain} before any
 * customer-facing use.
 */
@Configuration
@Profile("broadcast-demo")
public class DemoSecurityConfig {

    @Bean
    public SecurityFilterChain demoSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
