package io.github.marianciuc.streamingservice.subscription.service.impl;

import io.github.marianciuc.streamingservice.subscription.clients.OrderClient;
import io.github.marianciuc.streamingservice.subscription.dto.OrderResponse;
import io.github.marianciuc.streamingservice.subscription.dto.OrderCreationEventKafkaDto;
import io.github.marianciuc.streamingservice.subscription.dto.UserSubscriptionDto;
import io.github.marianciuc.streamingservice.subscription.entity.OrderStatus;
import io.github.marianciuc.streamingservice.subscription.entity.Subscription;
import io.github.marianciuc.streamingservice.subscription.entity.SubscriptionStatus;
import io.github.marianciuc.streamingservice.subscription.entity.UserSubscriptions;
import io.github.marianciuc.streamingservice.subscription.exceptions.NotFoundException;
import io.github.marianciuc.streamingservice.subscription.mapper.UserSubscriptionMapper;
import io.github.marianciuc.streamingservice.subscription.repository.UserSubscriptionRepository;
import io.github.marianciuc.streamingservice.subscription.service.SubscriptionService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpStatus.OK;

class UserSubscriptionServiceImplTest {

    private final UserSubscriptionRepository repository = mock(UserSubscriptionRepository.class);
    private final SubscriptionService subscriptionService = mock(SubscriptionService.class);
    private final OrderClient orderClient = mock(OrderClient.class);
    private final UserSubscriptionMapper mapper = mock(UserSubscriptionMapper.class);
    private final UserSubscriptionServiceImpl service =
            new UserSubscriptionServiceImpl(
                    repository,
                    subscriptionService,
                    orderClient,
                    mapper,
                    TransactionOperations.withoutTransaction()
            );

    @Test
    void subscribeUserExpiresPreviousActiveSubscriptionsBeforeCreatingNewOne() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();

        Subscription subscription = Subscription.builder()
                .id(subscriptionId)
                .durationInDays(30)
                .build();
        UserSubscriptions previousActive = UserSubscriptions.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .orderId(UUID.randomUUID())
                .status(SubscriptionStatus.ACTIVE)
                .endDate(LocalDate.now().plusDays(10))
                .build();

        when(subscriptionService.getSubscription(subscriptionId)).thenReturn(subscription);
        when(repository.findByOrderId(orderId)).thenReturn(Optional.empty());
        when(repository.findAllByUserIdAndStatusOrderByEndDateDesc(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(List.of(previousActive));

        service.subscribeUser(new OrderCreationEventKafkaDto(orderId, userId, subscriptionId));

        Assertions.assertEquals(SubscriptionStatus.EXPIRED, previousActive.getStatus());
        Assertions.assertEquals(LocalDate.now(), previousActive.getEndDate());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserSubscriptions>> expiredCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(expiredCaptor.capture());
        Assertions.assertEquals(1, expiredCaptor.getValue().size());
        Assertions.assertEquals(previousActive.getId(), expiredCaptor.getValue().getFirst().getId());

        ArgumentCaptor<UserSubscriptions> createdCaptor = ArgumentCaptor.forClass(UserSubscriptions.class);
        verify(repository).save(createdCaptor.capture());
        UserSubscriptions created = createdCaptor.getValue();
        Assertions.assertEquals(userId, created.getUserId());
        Assertions.assertEquals(orderId, created.getOrderId());
        Assertions.assertEquals(subscription, created.getSubscription());
        Assertions.assertEquals(SubscriptionStatus.ACTIVE, created.getStatus());
        Assertions.assertEquals(LocalDate.now(), created.getStartDate());
        Assertions.assertEquals(LocalDate.now().plusDays(subscription.getDurationInDays()), created.getEndDate());
    }

    @Test
    void getActiveSubscriptionReturnsLatestActiveRecord() {
        UUID userId = UUID.randomUUID();
        UserSubscriptions activeSubscription = UserSubscriptions.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .status(SubscriptionStatus.ACTIVE)
                .build();
        UserSubscriptionDto dto = mock(UserSubscriptionDto.class);

        when(repository.findFirstByUserIdAndStatusOrderByEndDateDesc(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(activeSubscription));
        when(mapper.toUserSubscriptionDto(activeSubscription)).thenReturn(dto);

        UserSubscriptionDto result = service.getActiveSubscription(userId);

        Assertions.assertSame(dto, result);
        verify(repository, never()).findByOrderId(any());
    }

    @Test
    void getActiveSubscriptionThrowsNotFoundWhenUserHasNoActiveRecord() {
        UUID userId = UUID.randomUUID();
        when(repository.findFirstByUserIdAndStatusOrderByEndDateDesc(userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.empty());

        NotFoundException exception = Assertions.assertThrows(
                NotFoundException.class,
                () -> service.getActiveSubscription(userId)
        );

        Assertions.assertEquals("User didn't have subscription", exception.getMessage());
        verify(mapper, never()).toUserSubscriptionDto(any());
    }

    @Test
    void subscribeUserDoesNothingWhenOrderActivationWasAlreadyApplied() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();

        Subscription subscription = Subscription.builder()
                .id(subscriptionId)
                .durationInDays(30)
                .build();
        UserSubscriptions existingSubscription = UserSubscriptions.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .orderId(orderId)
                .subscription(subscription)
                .status(SubscriptionStatus.ACTIVE)
                .startDate(LocalDate.now().minusDays(1))
                .endDate(LocalDate.now().plusDays(29))
                .build();

        when(subscriptionService.getSubscription(subscriptionId)).thenReturn(subscription);
        when(repository.findByOrderId(orderId)).thenReturn(Optional.of(existingSubscription));

        service.subscribeUser(new OrderCreationEventKafkaDto(orderId, userId, subscriptionId));

        verify(repository, never()).findAllByUserIdAndStatusOrderByEndDateDesc(any(), any());
        verify(repository, never()).save(any(UserSubscriptions.class));
        verify(repository, never()).saveAll(any());
    }

    @Test
    void extendSubscriptionUsesFollowOnPlanForTemporarySubscriptions() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID nextOrderId = UUID.randomUUID();
        UUID temporarySubscriptionId = UUID.randomUUID();
        UUID permanentSubscriptionId = UUID.randomUUID();

        Subscription permanentSubscription = Subscription.builder()
                .id(permanentSubscriptionId)
                .price(java.math.BigDecimal.valueOf(29.99))
                .durationInDays(30)
                .build();
        Subscription temporarySubscription = Subscription.builder()
                .id(temporarySubscriptionId)
                .price(java.math.BigDecimal.ZERO)
                .durationInDays(7)
                .isTemporary(true)
                .nextSubscription(permanentSubscription)
                .build();
        UserSubscriptions activeSubscription = UserSubscriptions.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .orderId(orderId)
                .subscription(temporarySubscription)
                .status(SubscriptionStatus.ACTIVE)
                .startDate(LocalDate.now().minusDays(7))
                .endDate(LocalDate.now())
                .build();
        when(repository.claimRenewal(
                eq(activeSubscription.getId()),
                eq(SubscriptionStatus.ACTIVE),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(1);
        when(repository.recordRenewalOrder(activeSubscription.getId(), SubscriptionStatus.ACTIVE, nextOrderId))
                .thenAnswer(invocation -> {
                    activeSubscription.setRenewalOrderId(nextOrderId);
                    return 1;
                });
        when(repository.findById(activeSubscription.getId())).thenReturn(Optional.of(activeSubscription));

        when(orderClient.createOrder(argThat(request ->
                userId.equals(request.userId())
                        && permanentSubscriptionId.equals(request.subscriptionId())
                        && permanentSubscription.getPrice().compareTo(request.price()) == 0
        ))).thenReturn(new ResponseEntity<>(
                new OrderResponse(
                        nextOrderId,
                        userId,
                        permanentSubscription.getPrice(),
                        permanentSubscriptionId,
                        LocalDateTime.now(),
                        OrderStatus.CREATED
                ),
                OK
        ));

        service.extendSubscription(activeSubscription);

        ArgumentCaptor<UserSubscriptions> createdCaptor = ArgumentCaptor.forClass(UserSubscriptions.class);
        verify(repository, times(2)).save(createdCaptor.capture());
        List<UserSubscriptions> savedSubscriptions = createdCaptor.getAllValues();
        Assertions.assertEquals(permanentSubscriptionId, savedSubscriptions.getFirst().getSubscription().getId());
        Assertions.assertEquals(nextOrderId, savedSubscriptions.getFirst().getOrderId());
        Assertions.assertEquals(SubscriptionStatus.ACTIVE, savedSubscriptions.getFirst().getStatus());
        Assertions.assertEquals(SubscriptionStatus.EXPIRED, savedSubscriptions.get(1).getStatus());
        verify(repository).claimRenewal(
                eq(activeSubscription.getId()),
                eq(SubscriptionStatus.ACTIVE),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        );
        verify(repository).recordRenewalOrder(activeSubscription.getId(), SubscriptionStatus.ACTIVE, nextOrderId);
    }

    @Test
    void extendSubscriptionFinalizesClaimedRenewalWithoutCreatingAnotherOrder() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID renewalOrderId = UUID.randomUUID();
        UUID temporarySubscriptionId = UUID.randomUUID();
        UUID permanentSubscriptionId = UUID.randomUUID();

        Subscription permanentSubscription = Subscription.builder()
                .id(permanentSubscriptionId)
                .price(java.math.BigDecimal.valueOf(29.99))
                .durationInDays(30)
                .build();
        Subscription temporarySubscription = Subscription.builder()
                .id(temporarySubscriptionId)
                .price(java.math.BigDecimal.ZERO)
                .durationInDays(7)
                .isTemporary(true)
                .nextSubscription(permanentSubscription)
                .build();
        UserSubscriptions claimedSubscription = UserSubscriptions.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .orderId(UUID.randomUUID())
                .subscription(temporarySubscription)
                .status(SubscriptionStatus.ACTIVE)
                .startDate(LocalDate.now().minusDays(7))
                .endDate(LocalDate.now())
                .renewalClaimedAt(LocalDateTime.now().minusMinutes(5))
                .renewalOrderId(renewalOrderId)
                .build();
        when(repository.findById(claimedSubscription.getId())).thenReturn(Optional.of(claimedSubscription));

        service.extendSubscription(claimedSubscription);

        ArgumentCaptor<UserSubscriptions> createdCaptor = ArgumentCaptor.forClass(UserSubscriptions.class);
        verify(repository, times(2)).save(createdCaptor.capture());
        List<UserSubscriptions> savedSubscriptions = createdCaptor.getAllValues();
        Assertions.assertEquals(renewalOrderId, savedSubscriptions.getFirst().getOrderId());
        Assertions.assertEquals(SubscriptionStatus.ACTIVE, savedSubscriptions.getFirst().getStatus());
        Assertions.assertEquals(SubscriptionStatus.EXPIRED, savedSubscriptions.get(1).getStatus());
        verify(orderClient, never()).createOrder(any());
    }

    @Test
    void extendSubscriptionReleasesClaimWhenOrderCreationFails() {
        UUID userId = UUID.randomUUID();
        Subscription subscription = Subscription.builder()
                .id(UUID.randomUUID())
                .price(java.math.BigDecimal.TEN)
                .durationInDays(30)
                .build();
        UserSubscriptions activeSubscription = UserSubscriptions.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .orderId(UUID.randomUUID())
                .subscription(subscription)
                .status(SubscriptionStatus.ACTIVE)
                .startDate(LocalDate.now().minusDays(30))
                .endDate(LocalDate.now())
                .build();
        when(repository.claimRenewal(
                eq(activeSubscription.getId()),
                eq(SubscriptionStatus.ACTIVE),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(1);
        when(orderClient.createOrder(any())).thenThrow(new RuntimeException("order service unavailable"));

        IOException exception = Assertions.assertThrows(
                IOException.class,
                () -> service.extendSubscription(activeSubscription)
        );

        Assertions.assertTrue(exception.getMessage().contains("order service unavailable"));
        verify(repository).releaseRenewalClaim(activeSubscription.getId());
    }

    @Test
    void extendSubscriptionReleasesClaimWhenRenewalTrackingCannotBePersisted() {
        UUID userId = UUID.randomUUID();
        UUID renewalOrderId = UUID.randomUUID();
        Subscription subscription = Subscription.builder()
                .id(UUID.randomUUID())
                .price(java.math.BigDecimal.TEN)
                .durationInDays(30)
                .build();
        UserSubscriptions activeSubscription = UserSubscriptions.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .orderId(UUID.randomUUID())
                .subscription(subscription)
                .status(SubscriptionStatus.ACTIVE)
                .startDate(LocalDate.now().minusDays(30))
                .endDate(LocalDate.now())
                .build();
        when(repository.claimRenewal(
                eq(activeSubscription.getId()),
                eq(SubscriptionStatus.ACTIVE),
                any(LocalDateTime.class),
                any(LocalDateTime.class)
        )).thenReturn(1);
        when(orderClient.createOrder(any())).thenReturn(new ResponseEntity<>(
                new OrderResponse(
                        renewalOrderId,
                        userId,
                        subscription.getPrice(),
                        subscription.getId(),
                        LocalDateTime.now(),
                        OrderStatus.CREATED
                ),
                OK
        ));
        when(repository.recordRenewalOrder(activeSubscription.getId(), SubscriptionStatus.ACTIVE, renewalOrderId)).thenReturn(0);

        IllegalStateException exception = Assertions.assertThrows(
                IllegalStateException.class,
                () -> service.extendSubscription(activeSubscription)
        );

        Assertions.assertTrue(exception.getMessage().contains("persist renewal order tracking"));
        verify(repository).releaseRenewalClaim(activeSubscription.getId());
    }
}
