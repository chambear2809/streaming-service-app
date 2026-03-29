package io.github.marianciuc.streamingservice.payment.specifications;

import io.github.marianciuc.streamingservice.payment.entity.Transaction;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionSpecificationTest {

    @Test
    void hasUserIdFiltersThroughCardHolderJoin() {
        UUID userId = UUID.randomUUID();
        Root<Transaction> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        @SuppressWarnings("rawtypes")
        Join cardHolderJoin = mock(Join.class);
        @SuppressWarnings("rawtypes")
        Path userIdPath = mock(Path.class);
        Predicate predicate = mock(Predicate.class);

        when(root.join("cardHolder")).thenReturn(cardHolderJoin);
        when(cardHolderJoin.get("userId")).thenReturn(userIdPath);
        when(criteriaBuilder.equal(userIdPath, userId)).thenReturn(predicate);

        Predicate result = TransactionSpecification.hasUserId(userId).toPredicate(root, query, criteriaBuilder);

        assertSame(predicate, result);
        verify(root).join("cardHolder");
    }
}
