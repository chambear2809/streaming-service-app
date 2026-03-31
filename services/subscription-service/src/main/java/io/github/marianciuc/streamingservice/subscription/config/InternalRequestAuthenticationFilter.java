package io.github.marianciuc.streamingservice.subscription.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

final class InternalRequestAuthenticationFilter extends OncePerRequestFilter {

    static final String AUTH_HEADER = "X-Streaming-Internal-Auth";
    static final String AUTHORITIES_HEADER = "X-Streaming-Authorities";

    private final String expectedSecret;

    InternalRequestAuthenticationFilter(String expectedSecret) {
        this.expectedSecret = expectedSecret;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/error".equals(path) || "/actuator/health".equals(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!StringUtils.hasText(expectedSecret)) {
            filterChain.doFilter(request, response);
            return;
        }

        String providedSecret = request.getHeader(AUTH_HEADER);
        if (!expectedSecret.equals(providedSecret)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid internal auth header.");
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            List<GrantedAuthority> authorities = parseAuthorities(request.getHeader(AUTHORITIES_HEADER));
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    "streaming-frontend",
                    null,
                    authorities
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    private List<GrantedAuthority> parseAuthorities(String headerValue) {
        if (!StringUtils.hasText(headerValue)) {
            return List.of(new SimpleGrantedAuthority("ROLE_SERVICE"));
        }

        List<GrantedAuthority> authorities = StringUtils.commaDelimitedListToSet(headerValue).stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();

        return authorities.isEmpty()
                ? List.of(new SimpleGrantedAuthority("ROLE_SERVICE"))
                : authorities;
    }
}
