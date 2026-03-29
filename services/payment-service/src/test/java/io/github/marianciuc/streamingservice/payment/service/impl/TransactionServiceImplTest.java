package io.github.marianciuc.streamingservice.payment.service.impl;

import io.github.marianciuc.streamingservice.payment.dto.common.Token;
import io.github.marianciuc.streamingservice.payment.dto.common.TransactionDto;
import io.github.marianciuc.streamingservice.payment.entity.CardHolder;
import io.github.marianciuc.streamingservice.payment.entity.JWTUserPrincipal;
import io.github.marianciuc.streamingservice.payment.entity.Transaction;
import io.github.marianciuc.streamingservice.payment.enums.Currency;
import io.github.marianciuc.streamingservice.payment.enums.PaymentStatus;
import io.github.marianciuc.streamingservice.payment.kafka.PaymentKafkaProducer;
import io.github.marianciuc.streamingservice.payment.repository.TransactionRepository;
import io.github.marianciuc.streamingservice.payment.service.CardHolderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceImplTest {

    @Mock
    private TransactionRepository repository;

    @Mock
    private CardHolderService cardHolderService;

    @Mock
    private PaymentKafkaProducer paymentKafkaProducer;

    @InjectMocks
    private TransactionServiceImpl transactionService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getTransactionsReturnsMappedPageContentForAuthenticatedUser() {
        UUID userId = UUID.randomUUID();
        JWTUserPrincipal principal = principal(userId, List.of("ROLE_USER"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );

        Transaction transaction = transaction(userId);
        when(repository.findAll(anySpecification(), eq(PageRequest.of(0, 10, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "amount")))))
                .thenReturn(new PageImpl<>(List.of(transaction)));

        List<TransactionDto> result = transactionService.getTransactions(0, 10, "asc", null, null);

        assertEquals(1, result.size());
        assertEquals(transaction.getId(), result.getFirst().id());
        assertEquals(userId, result.getFirst().userId());
    }

    @Test
    void findTransactionReturnsMappedDto() {
        UUID userId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        Transaction transaction = transaction(userId);
        transaction.setId(transactionId);
        when(repository.findById(transactionId)).thenReturn(Optional.of(transaction));

        TransactionDto result = transactionService.findTransaction(transactionId);

        assertEquals(transactionId, result.id());
        assertEquals(userId, result.userId());
        verify(repository).findById(transactionId);
    }

    @Test
    void getTransactionsAllowsAnonymousUnfilteredReadsWhenUserIdIsMissing() {
        UUID userId = UUID.randomUUID();
        Transaction transaction = transaction(userId);
        when(repository.findAll(anySpecification(), eq(PageRequest.of(0, 10, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "amount")))))
                .thenReturn(new PageImpl<>(List.of(transaction)));

        List<TransactionDto> result = transactionService.getTransactions(0, 10, "asc", null, null);

        assertEquals(1, result.size());
        assertEquals(transaction.getId(), result.getFirst().id());
        assertEquals(userId, result.getFirst().userId());
    }

    @SuppressWarnings("unchecked")
    private static Specification<Transaction> anySpecification() {
        return (Specification<Transaction>) any(Specification.class);
    }

    private static JWTUserPrincipal principal(UUID userId, List<String> roles) {
        return new JWTUserPrincipal(new Token(
                UUID.randomUUID(),
                userId,
                "demo-user",
                "issuer",
                roles,
                Instant.now(),
                Instant.now().plusSeconds(300)
        ));
    }

    private static Transaction transaction(UUID userId) {
        return Transaction.builder()
                .id(UUID.randomUUID())
                .orderId(UUID.randomUUID())
                .cardHolder(CardHolder.builder().userId(userId).build())
                .currency(Currency.USD)
                .amount(12_500L)
                .status(PaymentStatus.SUCCESS)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
