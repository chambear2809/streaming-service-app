/*
 * Copyright (c) 2024  Vladimir Marianciuc. All Rights Reserved.
 *
 * Project: STREAMING SERVICE APP
 * File: CustomerServiceImpl.java
 *
 */

package io.github.marianciuc.streamingservice.customer.services.impl;

import io.github.marianciuc.streamingservice.customer.dto.CustomerDto;
import io.github.marianciuc.streamingservice.customer.dto.PaginationResponse;
import io.github.marianciuc.streamingservice.customer.exceptions.EntityNotFoundException;
import io.github.marianciuc.streamingservice.customer.exceptions.VerificationCodeException;
import io.github.marianciuc.streamingservice.customer.kafka.messages.CreateUserMessage;
import io.github.marianciuc.streamingservice.customer.model.Customer;
import io.github.marianciuc.streamingservice.customer.repository.CustomerRepository;
import io.github.marianciuc.streamingservice.customer.services.CustomerService;
import io.github.marianciuc.streamingservice.customer.services.EmailVerificationService;
import io.github.marianciuc.streamingservice.customer.security.services.UserService;
import io.github.marianciuc.streamingservice.customer.specifications.CustomerSpecification;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service class that handles operations related to customers, such as creating a customer,
 * starting email verification, verifying email codes, and finding customers based on filters.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerServiceImpl implements CustomerService {

    private static class Defaults {
        static final String THEME = "light";
        static final String LANGUAGE = "en";
        static final boolean RECEIVE_NEWSLETTER = false;
        static final boolean ENABLE_NOTIFICATIONS = true;
        static final boolean PROFILE_IS_COMPLETED = false;
        static final boolean EMAIL_VERIFIED = false;
    }

    private final CustomerRepository repository;
    private final EmailVerificationService emailVerificationService;
    private final UserService userService;
    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;

    @Override
    public void createCustomer(CreateUserMessage message) {
        Observation observation = Observation.createNotStarted("customer.provisioning", observationRegistry)
                .contextualName("provision customer from user-created event")
                .lowCardinalityKeyValue("source", "user-created-topic");
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "failure";

        observation.start();
        try (Observation.Scope scope = observation.openScope()) {
            if (repository.existsById(message.id())) {
                outcome = "duplicate";
                meterRegistry.counter("streaming.customer.provisioning.total", "outcome", "duplicate").increment();
                meterRegistry.counter("streaming.customer.provisioning.duplicates.total").increment();
                log.debug("Customer {} already exists, skipping duplicate provisioning event.", message.id());
                return;
            }

            Customer newCustomer = Customer.builder()
                    .id(message.id())
                    .email(message.email())
                    .theme(Defaults.THEME)
                    .preferredLanguage(Defaults.LANGUAGE)
                    .profilePicture("")
                    .country("")
                    .birthDate(LocalDate.now())
                    .profileIsCompleted(Defaults.PROFILE_IS_COMPLETED)
                    .isEmailVerified(Defaults.EMAIL_VERIFIED)
                    .receiveNewsletter(Defaults.RECEIVE_NEWSLETTER)
                    .enableNotifications(Defaults.ENABLE_NOTIFICATIONS)
                    .username(message.username())
                    .build();
            log.info("Creating new customer {}", newCustomer);
            this.repository.save(newCustomer);
            outcome = "created";
            meterRegistry.counter("streaming.customer.provisioning.total", "outcome", "created").increment();
        } catch (RuntimeException exception) {
            observation.error(exception);
            meterRegistry.counter("streaming.customer.provisioning.total", "outcome", "failure").increment();
            throw exception;
        } finally {
            sample.stop(Timer.builder("streaming.customer.provisioning.duration")
                    .description("Latency of provisioning customer records from Kafka events.")
                    .tag("outcome", outcome)
                    .register(meterRegistry));
            observation.stop();
        }
    }

    public void startEmailVerification() {
        Customer customer = this.getCustomerFromAuth();

        if (customer.isEmailVerified()) {
            log.error("Email {} is already verified", customer.getEmail());
            throw new VerificationCodeException("Email is already verified");
        }

        this.emailVerificationService.sendVerificationEmail(customer.getEmail());
    }

    @Override
    public void verifyCode(String verifyCode) {
        Customer customer = this.getCustomerFromAuth();

        try {
            String verifiedEmail = emailVerificationService.verifyEmail(verifyCode);
            if (!verifiedEmail.equals(customer.getEmail())) {
                String errorMsg = String.format("Email %s does not match the authenticated user %s", verifiedEmail, customer.getEmail());
                log.error(errorMsg);
                throw new VerificationCodeException("Invalid verification code");
            }
            customer.setEmailVerified(true);
            this.repository.save(customer);
        } catch (VerificationCodeException e) {
            log.error("Verification code {} is invalid or expired", verifyCode);
            throw e;
        }
    }

    private Customer getCustomerFromAuth() {
        return this.repository.findById(this.userService.extractUserIdFromAuth()).orElseThrow(() -> new EntityNotFoundException("Customer not found"));
    }

    @Override
    public PaginationResponse<List<CustomerDto>> findAllByFilter(Integer page, Integer pageSize, String country, String email, String id, Boolean isEmailVerified, String username) {
        int requestedPage = page == null ? 1 : Math.max(page, 1);
        int requestedPageSize = pageSize == null ? 10 : Math.max(pageSize, 1);
        Pageable pageable = PageRequest.of(requestedPage - 1, requestedPageSize);

        Specification<Customer> spec = Specification.where(
                        Optional.ofNullable(country).map(CustomerSpecification::hasCountry).orElse(null))
                .and(Optional.ofNullable(email).map(CustomerSpecification::emailStartsWith).orElse(null))
                .and(Optional.ofNullable(username).map(CustomerSpecification::usernameStartsWith).orElse(null))
                .and(Optional.ofNullable(id).map(CustomerSpecification::idStartsWith).orElse(null))
                .and(Optional.ofNullable(isEmailVerified).map(CustomerSpecification::isEmailVerified).orElse(null));

        Page<Customer> customerPage = this.repository.findAll(spec, pageable);

        return new PaginationResponse<>(
                customerPage.getTotalPages(),
                customerPage.getNumber() + 1,
                customerPage.getSize(),
                customerPage.getContent().stream().map(CustomerDto::to).toList()
        );
    }

    public CustomerDto findById(UUID customerId) {
        return this.repository.findById(customerId).map(CustomerDto::to).orElseThrow(() -> new EntityNotFoundException(
                "Customer not found"));
    }

    @Override
    public void updateCustomerDetails(CustomerDto customerDto) {
        Customer existingCustomer = this.getCustomerFromAuth();

        if (customerDto.email() != null) {
            existingCustomer.setEmail(customerDto.email());
            existingCustomer.setEmailVerified(false);
        }
        if (customerDto.theme() != null) existingCustomer.setTheme(customerDto.theme());
        if (customerDto.birthDate() != null) existingCustomer.setBirthDate(customerDto.birthDate());
        if (customerDto.country() != null) existingCustomer.setCountry(customerDto.country());
        if (customerDto.profilePicture() != null) existingCustomer.setProfilePicture(customerDto.profilePicture());
        if (customerDto.preferredLanguage() != null) existingCustomer.setPreferredLanguage(customerDto.preferredLanguage());
        if (customerDto.receiveNewsletter() != existingCustomer.isReceiveNewsletter()) existingCustomer.setReceiveNewsletter(customerDto.receiveNewsletter());
        if (customerDto.enableNotifications() != existingCustomer.isEnableNotifications()) existingCustomer.setEnableNotifications(customerDto.enableNotifications());

        this.repository.save(existingCustomer);
    }
}
