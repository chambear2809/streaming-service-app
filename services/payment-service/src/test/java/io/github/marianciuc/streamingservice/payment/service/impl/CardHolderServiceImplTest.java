package io.github.marianciuc.streamingservice.payment.service.impl;

import io.github.marianciuc.streamingservice.payment.dto.common.Token;
import io.github.marianciuc.streamingservice.payment.dto.requests.UpdateCardHolderRequest;
import io.github.marianciuc.streamingservice.payment.entity.Address;
import io.github.marianciuc.streamingservice.payment.entity.CardHolder;
import io.github.marianciuc.streamingservice.payment.entity.JWTUserPrincipal;
import io.github.marianciuc.streamingservice.payment.repository.CardHolderRepository;
import io.github.marianciuc.streamingservice.payment.service.AddressService;
import io.github.marianciuc.streamingservice.payment.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardHolderServiceImplTest {

    @Mock
    private AddressService addressService;

    @Mock
    private CardHolderRepository repository;

    @Mock
    private UserService userService;

    @InjectMocks
    private CardHolderServiceImpl service;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void findCardHolderIgnoresRequestedIdForNonAdminUsers() {
        UUID authenticatedUserId = UUID.randomUUID();
        UUID requestedUserId = UUID.randomUUID();
        CardHolder authenticatedCardHolder = cardHolder(authenticatedUserId);

        when(userService.hasAdminRoles()).thenReturn(false);
        when(userService.extractUserIdFromAuth()).thenReturn(authenticatedUserId);
        when(repository.findById(authenticatedUserId)).thenReturn(Optional.of(authenticatedCardHolder));

        service.findCardHolder(requestedUserId);

        verify(repository).findById(authenticatedUserId);
        verify(repository, never()).findById(requestedUserId);
    }

    @Test
    void updateCardHolderAppliesPhoneAndEmailWithoutRequiringName() {
        UUID userId = UUID.randomUUID();
        CardHolder cardHolder = cardHolder(userId);
        cardHolder.setCardHolderName("Existing Name");

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal(userId), null, principal(userId).getAuthorities())
        );
        when(repository.findById(userId)).thenReturn(Optional.of(cardHolder));

        service.updateCardHolder(new UpdateCardHolderRequest(null, "555-0100", "updated@example.com"));

        assertEquals("Existing Name", cardHolder.getCardHolderName());
        assertEquals("555-0100", cardHolder.getPhoneNumber());
        assertEquals("updated@example.com", cardHolder.getEmail());
        verify(repository).save(cardHolder);
    }

    @Test
    void updateCardHolderAppliesPhoneAndEmailWhenNameIsBlank() {
        UUID userId = UUID.randomUUID();
        CardHolder cardHolder = cardHolder(userId);
        cardHolder.setCardHolderName("Existing Name");

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal(userId), null, principal(userId).getAuthorities())
        );
        when(repository.findById(userId)).thenReturn(Optional.of(cardHolder));

        service.updateCardHolder(new UpdateCardHolderRequest("", "555-0199", "blank-name@example.com"));

        assertEquals("Existing Name", cardHolder.getCardHolderName());
        assertEquals("555-0199", cardHolder.getPhoneNumber());
        assertEquals("blank-name@example.com", cardHolder.getEmail());
        verify(repository).save(cardHolder);
    }

    private static CardHolder cardHolder(UUID userId) {
        return CardHolder.builder()
                .userId(userId)
                .stripeCustomerId("cus_demo")
                .address(Address.builder()
                        .id(UUID.randomUUID())
                        .line1("1 Demo Way")
                        .city("New York")
                        .postalCode("10001")
                        .state("NY")
                        .country("US")
                        .build())
                .email("existing@example.com")
                .phoneNumber("555-0000")
                .build();
    }

    private static JWTUserPrincipal principal(UUID userId) {
        return new JWTUserPrincipal(new Token(
                UUID.randomUUID(),
                userId,
                "demo-user",
                "issuer",
                List.of("ROLE_USER"),
                Instant.now(),
                Instant.now().plusSeconds(300)
        ));
    }
}
