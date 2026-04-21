package io.github.marianciuc.streamingservice.user.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Demo-profile security configuration — intentionally permits all requests.
 *
 * <p>Active only when {@code spring.profiles.active=demo}. All HTTP security is disabled
 * so the demo auth controller endpoints are reachable without JWT credentials.
 *
 * <p><strong>Do not enable this profile on a network-accessible deployment.</strong>
 */
@Configuration
@Profile("demo")
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
