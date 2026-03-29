package io.github.marianciuc.streamingservice.customer.specifications;

import io.github.marianciuc.streamingservice.customer.model.Customer;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerSpecificationTest {

    @Test
    void idStartsWithCastsUuidFieldToStringBeforeLikeComparison() {
        Root<Customer> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
        @SuppressWarnings("unchecked")
        Path<Object> idPath = mock(Path.class);
        @SuppressWarnings("unchecked")
        Path<String> idAsStringPath = mock(Path.class);
        Predicate predicate = mock(Predicate.class);

        when(root.get("id")).thenReturn(idPath);
        when(idPath.as(String.class)).thenReturn(idAsStringPath);
        when(criteriaBuilder.like(idAsStringPath, "abc%")).thenReturn(predicate);

        Predicate result = CustomerSpecification.idStartsWith("abc").toPredicate(root, query, criteriaBuilder);

        assertSame(predicate, result);
        verify(idPath).as(String.class);
    }
}
