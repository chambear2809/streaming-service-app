package io.github.marianciuc.streamingservice.subscription.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class InternalRequestAuthenticationFilterTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsRequestsWithoutMatchingSecret() throws ServletException, IOException {
        InternalRequestAuthenticationFilter filter = new InternalRequestAuthenticationFilter("test-secret");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/subscription/all");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void skipsSecretCheckForHealthEndpoint() throws ServletException, IOException {
        InternalRequestAuthenticationFilter filter = new InternalRequestAuthenticationFilter("test-secret");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void assignsServiceRoleWhenAuthoritiesHeaderIsMissing() throws ServletException, IOException {
        InternalRequestAuthenticationFilter filter = new InternalRequestAuthenticationFilter("test-secret");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/subscription/all");
        request.addHeader("X-Streaming-Internal-Auth", "test-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertEquals("ROLE_SERVICE", authentication.getAuthorities().iterator().next().getAuthority());
        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }
}
