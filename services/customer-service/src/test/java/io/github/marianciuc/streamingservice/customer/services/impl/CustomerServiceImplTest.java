package io.github.marianciuc.streamingservice.customer.services.impl;

import io.github.marianciuc.streamingservice.customer.dto.CustomerDto;
import io.github.marianciuc.streamingservice.customer.dto.PaginationResponse;
import io.github.marianciuc.streamingservice.customer.kafka.messages.CreateUserMessage;
import io.github.marianciuc.streamingservice.customer.model.Customer;
import io.github.marianciuc.streamingservice.customer.repository.CustomerRepository;
import io.github.marianciuc.streamingservice.customer.security.services.UserService;
import io.github.marianciuc.streamingservice.customer.services.EmailVerificationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

    private CustomerServiceImpl customerService;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        customerService = new CustomerServiceImpl(
                repository,
                emailVerificationService,
                userService,
                meterRegistry,
                ObservationRegistry.create()
        );
    }

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

    @Test
    void createCustomerSkipsDuplicateProvisioningEvent() {
        UUID customerId = UUID.randomUUID();
        when(repository.existsById(customerId)).thenReturn(true);

        customerService.createCustomer(new CreateUserMessage(customerId, "demo@example.com", "demo"));

        verify(repository, never()).save(any(Customer.class));
        assertEquals(1.0, meterRegistry.get("streaming.customer.provisioning.total").tag("outcome", "duplicate").counter().count());
        assertEquals(1.0, meterRegistry.get("streaming.customer.provisioning.duplicates.total").counter().count());
        assertEquals(1L, meterRegistry.get("streaming.customer.provisioning.duration").tag("outcome", "duplicate").timer().count());
    }

    @Test
    void createCustomerRecordsCreatedMetric() {
        UUID customerId = UUID.randomUUID();
        when(repository.existsById(customerId)).thenReturn(false);

        customerService.createCustomer(new CreateUserMessage(customerId, "demo@example.com", "demo"));

        verify(repository).save(any(Customer.class));
        assertEquals(1.0, meterRegistry.get("streaming.customer.provisioning.total").tag("outcome", "created").counter().count());
        assertEquals(1L, meterRegistry.get("streaming.customer.provisioning.duration").tag("outcome", "created").timer().count());
    }

    @Test
    void createCustomerRecordsFailureMetricWhenSaveFails() {
        UUID customerId = UUID.randomUUID();
        when(repository.existsById(customerId)).thenReturn(false);
        when(repository.save(any(Customer.class))).thenThrow(new IllegalStateException("db down"));

        assertThrows(
                IllegalStateException.class,
                () -> customerService.createCustomer(new CreateUserMessage(customerId, "demo@example.com", "demo"))
        );

        assertEquals(1.0, meterRegistry.get("streaming.customer.provisioning.total").tag("outcome", "failure").counter().count());
        assertEquals(1L, meterRegistry.get("streaming.customer.provisioning.duration").tag("outcome", "failure").timer().count());
    }

    @SuppressWarnings("unchecked")
    private static Specification<Customer> anySpecification() {
        return (Specification<Customer>) any(Specification.class);
    }
}
