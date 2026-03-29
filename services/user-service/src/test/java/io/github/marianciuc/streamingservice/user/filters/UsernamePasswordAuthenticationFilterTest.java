package io.github.marianciuc.streamingservice.user.filters;

import io.github.marianciuc.streamingservice.user.dto.common.Token;
import io.github.marianciuc.streamingservice.user.factories.AuthenticationTokenFactory;
import io.github.marianciuc.streamingservice.user.factories.TokenFactory;
import io.github.marianciuc.streamingservice.user.serializers.TokenSerializer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationConverter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsernamePasswordAuthenticationFilterTest {

    @Test
    void successfulAuthenticationSerializesTheAccessTokenInsteadOfTheRefreshToken() throws IOException {
        AuthenticationConverter authenticationConverter = mock(AuthenticationConverter.class);
        TokenFactory accessTokenFactory = mock(TokenFactory.class);
        AuthenticationTokenFactory refreshTokenFactory = mock(AuthenticationTokenFactory.class);
        TokenSerializer accessTokenSerializer = mock(TokenSerializer.class);
        TokenSerializer refreshTokenSerializer = mock(TokenSerializer.class);
        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
        Authentication authentication = mock(Authentication.class);

        Token refreshToken = token("refresh-user");
        Token accessToken = token("access-user");

        when(refreshTokenFactory.apply(authentication)).thenReturn(refreshToken);
        when(accessTokenFactory.apply(refreshToken)).thenReturn(accessToken);
        when(refreshTokenSerializer.apply(refreshToken)).thenReturn("refresh-token");
        when(accessTokenSerializer.apply(accessToken)).thenReturn("access-token");

        TestableUsernamePasswordAuthenticationFilter filter = new TestableUsernamePasswordAuthenticationFilter(
                authenticationConverter,
                accessTokenFactory,
                refreshTokenFactory,
                accessTokenSerializer,
                refreshTokenSerializer,
                authenticationManager
        );

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.invokeSuccessfulAuthentication(response, authentication);

        verify(accessTokenSerializer).apply(accessToken);
        verify(accessTokenSerializer, never()).apply(refreshToken);
        assertTrue(response.getContentAsString().contains("\"accessToken\":\"access-token\""));
        assertTrue(response.getContentAsString().contains("\"refreshToken\":\"refresh-token\""));
    }

    private static Token token(String subject) {
        return new Token(
                UUID.randomUUID(),
                UUID.randomUUID(),
                subject,
                "issuer",
                List.of("ROLE_USER"),
                Instant.now(),
                Instant.now().plusSeconds(60)
        );
    }

    private static final class TestableUsernamePasswordAuthenticationFilter extends UsernamePasswordAuthenticationFilter {

        private TestableUsernamePasswordAuthenticationFilter(
                AuthenticationConverter authenticationConverter,
                TokenFactory accessTokenFactory,
                AuthenticationTokenFactory refreshTokenFactory,
                TokenSerializer accessTokenSerializer,
                TokenSerializer refreshTokenSerializer,
                AuthenticationManager authenticationManager
        ) {
            super(authenticationConverter, accessTokenFactory, refreshTokenFactory, accessTokenSerializer, refreshTokenSerializer, authenticationManager);
        }

        private void invokeSuccessfulAuthentication(HttpServletResponse response, Authentication authentication) throws IOException {
            successfulAuthentication(
                    new MockHttpServletRequest(),
                    response,
                    mock(FilterChain.class),
                    authentication
            );
        }
    }
}
