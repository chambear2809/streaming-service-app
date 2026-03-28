package io.github.marianciuc.streamingservice.billing.security.filters;

import io.github.marianciuc.streamingservice.billing.security.dto.JWTUserPrincipal;
import io.github.marianciuc.streamingservice.billing.security.dto.Token;
import io.github.marianciuc.streamingservice.billing.security.serialization.TokenDeserializer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class JWTFilter extends OncePerRequestFilter {

    private final TokenDeserializer tokenDeserializer;

    public JWTFilter(TokenDeserializer tokenDeserializer) {
        this.tokenDeserializer = tokenDeserializer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            String token = authorizationHeader.substring(7);
            Token jwtToken = tokenDeserializer.apply(token);

            if (jwtToken == null) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid Token");
                return;
            }

            JWTUserPrincipal jwtUserPrincipal = new JWTUserPrincipal(jwtToken);
            Authentication authentication = new UsernamePasswordAuthenticationToken(
                    jwtUserPrincipal,
                    "",
                    jwtUserPrincipal.getAuthorities()
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}
