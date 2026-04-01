/*
 * Copyright (c) 2024 Vladimir Marianciuc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *   The above copyright notice and this permission notice shall be included in
 *    all copies or substantial portions of the Software.
 *
 *    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *     AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *     LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *     OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *      THE SOFTWARE.
 */

package io.github.marianciuc.streamingservice.subscription.service.impl;

import io.github.marianciuc.streamingservice.subscription.clients.OrderClient;
import io.github.marianciuc.streamingservice.subscription.dto.CreateOrderRequest;
import io.github.marianciuc.streamingservice.subscription.dto.OrderCreationEventKafkaDto;
import io.github.marianciuc.streamingservice.subscription.dto.OrderResponse;
import io.github.marianciuc.streamingservice.subscription.dto.UserSubscriptionDto;
import io.github.marianciuc.streamingservice.subscription.entity.Subscription;
import io.github.marianciuc.streamingservice.subscription.entity.SubscriptionStatus;
import io.github.marianciuc.streamingservice.subscription.entity.UserSubscriptions;
import io.github.marianciuc.streamingservice.subscription.exceptions.NotFoundException;
import io.github.marianciuc.streamingservice.subscription.mapper.UserSubscriptionMapper;
import io.github.marianciuc.streamingservice.subscription.repository.UserSubscriptionRepository;
import io.github.marianciuc.streamingservice.subscription.service.SubscriptionService;
import io.github.marianciuc.streamingservice.subscription.service.UserSubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import javax.naming.OperationNotSupportedException;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * UserSubscriptionService is a class that provides methods for managing user subscriptions.
 */
@Service
@RequiredArgsConstructor
public class UserSubscriptionServiceImpl implements UserSubscriptionService {

    private static final Duration RENEWAL_CLAIM_TIMEOUT = Duration.ofMinutes(5);

    private final UserSubscriptionRepository repository;
    private final SubscriptionService subscriptionService;
    private final OrderClient orderClient;
    private final UserSubscriptionMapper mapper;
    private final TransactionOperations transactionOperations;


    public void subscribeUser(OrderCreationEventKafkaDto orderCreationEventKafkaDto) {
        Subscription subscription = subscriptionService.getSubscription(orderCreationEventKafkaDto.subscriptionId());
        Optional<UserSubscriptions> userSubscriptionsOptional = repository.findByOrderId(orderCreationEventKafkaDto.orderId());

        if (userSubscriptionsOptional.isPresent()
                && activationAlreadyApplied(userSubscriptionsOptional.get(), subscription, orderCreationEventKafkaDto.userId())) {
            return;
        }

        expireOtherActiveSubscriptions(orderCreationEventKafkaDto.userId(), orderCreationEventKafkaDto.orderId());

        if (userSubscriptionsOptional.isPresent()) {
            UserSubscriptions userSubscriptions = userSubscriptionsOptional.get();

            if (!userSubscriptions.getSubscription().equals(subscription)) {
                userSubscriptions.setSubscription(subscription);
            }

            userSubscriptions.setStartDate(LocalDate.now());
            userSubscriptions.setEndDate(LocalDate.now().plusDays(subscription.getDurationInDays()));
            userSubscriptions.setStatus(SubscriptionStatus.ACTIVE);
            userSubscriptions.setRenewalClaimedAt(null);
            userSubscriptions.setRenewalOrderId(null);
            repository.save(userSubscriptions);
        } else {
            UserSubscriptions userSubscription = createUserSubscription(subscription, orderCreationEventKafkaDto.userId(), orderCreationEventKafkaDto.orderId());
            repository.save(userSubscription);
        }
    }

    public List<UserSubscriptions> getAllUserSubscriptionsByStatusAndEndDate(SubscriptionStatus status, LocalDate endDate) {
        return repository.findAllByStatusAndEndDate(status, endDate);
    }

    public List<UserSubscriptions> getAllUserSubscriptionsByStatusEndingOnOrBefore(SubscriptionStatus status, LocalDate endDate) {
        return repository.findAllEligibleForRenewal(status, endDate, claimExpiredBefore());
    }

    public List<UserSubscriptions> getRenewalsReadyToFinalize() {
        return repository.findAllPendingRenewalFinalization(SubscriptionStatus.ACTIVE);
    }

    @Override
    public UserSubscriptionDto getActiveSubscription(UUID userId) {
        return repository.findFirstByUserIdAndStatusOrderByEndDateDesc(userId, SubscriptionStatus.ACTIVE)
                .map(mapper::toUserSubscriptionDto)
                .orElseThrow(() -> new NotFoundException("User didn't have subscription"));
    }

    public void cancelSubscription(UserSubscriptions subscription) throws OperationNotSupportedException {
        if (!subscription.getStatus().equals(SubscriptionStatus.CANCELLED)) {
            subscription.setStatus(SubscriptionStatus.CANCELLED);
            repository.save(subscription);
            // TODO send email to user that subscription is cancelled
        } else {
            throw new OperationNotSupportedException("Not allowed to repeat cancellation of subscription.");
        }
    }

    public void cancelSubscription(UUID subscriptionId) {
        UserSubscriptions userSubscriptions = repository.findById(subscriptionId).orElseThrow(() -> new NotFoundException("Subscription not found"));
        userSubscriptions.setStatus(SubscriptionStatus.CANCELLED);
        repository.save(userSubscriptions);
    }

    public void extendSubscription(UserSubscriptions userSubscription) throws IOException, OperationNotSupportedException {
        Subscription subscription = userSubscription.getSubscription();
        Subscription renewedSubscription = resolveRenewedSubscription(subscription);

        if (userSubscription.getRenewalOrderId() != null) {
            finalizeRenewal(userSubscription.getId(), renewedSubscription, userSubscription.getRenewalOrderId());
            return;
        }

        if (!claimRenewal(userSubscription.getId())) {
            return;
        }

        CreateOrderRequest createOrderRequest = new CreateOrderRequest(
                userSubscription.getUserId(),
                renewedSubscription.getId(),
                renewedSubscription.getPrice()
        );

        ResponseEntity<OrderResponse> response;
        try {
            response = orderClient.createOrder(createOrderRequest);
        } catch (Exception e) {
            releaseRenewalClaim(userSubscription.getId());
            throw new IOException("Unable to extend subscription due to: " + e.getMessage());
        }

        boolean renewalTracked = false;
        try {
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IOException("Unable to extend subscription due to: " + response.getStatusCode());
            }

            UUID renewalOrderId = extractRenewalOrderId(response);
            recordRenewalOrder(userSubscription.getId(), renewalOrderId);
            renewalTracked = true;
            finalizeRenewal(userSubscription.getId(), renewedSubscription, renewalOrderId);
        } catch (IOException | RuntimeException exception) {
            if (!renewalTracked) {
                releaseRenewalClaim(userSubscription.getId());
            }
            throw exception;
        }
    }

    public void unsubscribeUser(UserSubscriptions subscription) throws OperationNotSupportedException {
        if (!subscription.getStatus().equals(SubscriptionStatus.EXPIRED)) {
            subscription.setStatus(SubscriptionStatus.EXPIRED);
            repository.save(subscription);
            // Send topic to users to change role
            // send topic to notify user
        } else {
            throw new OperationNotSupportedException("User is already unsubscribed.");
        }
    }

    private UserSubscriptions createUserSubscription(Subscription subscription, UUID userId, UUID orderId) {
        return UserSubscriptions.builder()
                .subscription(subscription)
                .orderId(orderId)
                .userId(userId)
                .status(SubscriptionStatus.ACTIVE)
                .startDate(LocalDate.now())
                .endDate(LocalDate.now().plusDays(subscription.getDurationInDays()))
                .build();
    }

    private Subscription resolveRenewedSubscription(Subscription subscription) {
        if (!Boolean.TRUE.equals(subscription.getIsTemporary())) {
            return subscription;
        }
        if (subscription.getNextSubscription() == null) {
            throw new IllegalStateException("Temporary subscription is missing its follow-on subscription");
        }
        return subscription.getNextSubscription();
    }

    private boolean claimRenewal(UUID subscriptionId) {
        LocalDateTime claimedAt = LocalDateTime.now();
        Integer updatedRows = transactionOperations.execute(status -> repository.claimRenewal(
                subscriptionId,
                SubscriptionStatus.ACTIVE,
                claimedAt,
                claimedAt.minus(RENEWAL_CLAIM_TIMEOUT)
        ));
        return updatedRows != null && updatedRows == 1;
    }

    private void releaseRenewalClaim(UUID subscriptionId) {
        transactionOperations.executeWithoutResult(status -> repository.releaseRenewalClaim(subscriptionId));
    }

    private void recordRenewalOrder(UUID subscriptionId, UUID renewalOrderId) {
        Integer updatedRows = transactionOperations.execute(status -> repository.recordRenewalOrder(
                subscriptionId,
                SubscriptionStatus.ACTIVE,
                renewalOrderId
        ));
        if (updatedRows == null || updatedRows != 1) {
            throw new IllegalStateException("Unable to persist renewal order tracking");
        }
    }

    private void finalizeRenewal(UUID subscriptionId, Subscription renewedSubscription, UUID renewalOrderId) {
        transactionOperations.executeWithoutResult(status -> {
            UserSubscriptions managedSubscription = requireManagedSubscription(subscriptionId);
            if (!Objects.equals(managedSubscription.getRenewalOrderId(), renewalOrderId)) {
                throw new IllegalStateException("Renewal order tracking is out of sync");
            }

            UserSubscriptions newUserSubscription = createUserSubscription(
                    renewedSubscription,
                    managedSubscription.getUserId(),
                    renewalOrderId
            );
            repository.save(newUserSubscription);

            managedSubscription.setStatus(SubscriptionStatus.EXPIRED);
            managedSubscription.setRenewalClaimedAt(null);
            managedSubscription.setRenewalOrderId(null);
            if (managedSubscription.getEndDate() == null || managedSubscription.getEndDate().isAfter(LocalDate.now())) {
                managedSubscription.setEndDate(LocalDate.now());
            }
            repository.save(managedSubscription);
        });
    }

    private UserSubscriptions requireManagedSubscription(UUID subscriptionId) {
        return repository.findById(subscriptionId)
                .orElseThrow(() -> new NotFoundException("Subscription not found"));
    }

    private UUID extractRenewalOrderId(ResponseEntity<OrderResponse> response) throws IOException {
        OrderResponse body = response.getBody();
        if (body == null || body.id() == null) {
            throw new IOException("Unable to extend subscription due to missing order response body");
        }
        return body.id();
    }

    private boolean activationAlreadyApplied(UserSubscriptions existingSubscription, Subscription subscription, UUID userId) {
        return Objects.equals(existingSubscription.getUserId(), userId)
                && existingSubscription.getSubscription() != null
                && Objects.equals(existingSubscription.getSubscription().getId(), subscription.getId());
    }

    private LocalDateTime claimExpiredBefore() {
        return LocalDateTime.now().minus(RENEWAL_CLAIM_TIMEOUT);
    }

    private void expireOtherActiveSubscriptions(UUID userId, UUID currentOrderId) {
        List<UserSubscriptions> activeSubscriptions = repository
                .findAllByUserIdAndStatusOrderByEndDateDesc(userId, SubscriptionStatus.ACTIVE)
                .stream()
                .filter(subscription -> !Objects.equals(subscription.getOrderId(), currentOrderId))
                .peek(subscription -> {
                    subscription.setStatus(SubscriptionStatus.EXPIRED);
                    if (subscription.getEndDate() == null || subscription.getEndDate().isAfter(LocalDate.now())) {
                        subscription.setEndDate(LocalDate.now());
                    }
                })
                .collect(Collectors.toList());

        if (!activeSubscriptions.isEmpty()) {
            repository.saveAll(activeSubscriptions);
        }
    }
}
