package io.github.marianciuc.streamingservice.customer.services.impl;

import io.github.marianciuc.streamingservice.customer.dto.CustomerDto;
import io.github.marianciuc.streamingservice.customer.dto.PaginationResponse;
import io.github.marianciuc.streamingservice.customer.model.Customer;
import io.github.marianciuc.streamingservice.customer.repository.CustomerRepository;
import io.github.marianciuc.streamingservice.customer.security.services.UserService;
import io.github.marianciuc.streamingservice.customer.services.EmailVerificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceImplTest {

    @Mock
    private CustomerRepository repository;

    @Mock
    private EmailVerificationService emailVerificationService;

    @Mock
    private UserService userService;

    @InjectMocks
    private CustomerServiceImpl customerService;

    @Test
    void findAllByFilterUsesOneBasedPageNumbersAndKeepsResponseOneBased() {
        Customer customer = Customer.builder()
                .id(UUID.randomUUID())
                .username("demo")
                .email("demo@example.com")
                .build();
        when(repository.findAll(anySpecification(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(customer)));

        PaginationResponse<List<CustomerDto>> response =
                customerService.findAllByFilter(1, 10, null, null, null, null, null);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(anySpecification(), pageableCaptor.capture());
        assertEquals(0, pageableCaptor.getValue().getPageNumber());
        assertEquals(10, pageableCaptor.getValue().getPageSize());
        assertEquals(1, response.currentPage());
        assertEquals(1, response.data().size());
    }

    @SuppressWarnings("unchecked")
    private static Specification<Customer> anySpecification() {
        return (Specification<Customer>) any(Specification.class);
    }
}
