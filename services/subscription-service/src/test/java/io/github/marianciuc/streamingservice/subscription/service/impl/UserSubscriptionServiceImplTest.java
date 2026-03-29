package io.github.marianciuc.streamingservice.subscription.service.impl;

import io.github.marianciuc.streamingservice.subscription.clients.OrderClient;
import io.github.marianciuc.streamingservice.subscription.dto.OrderCreationEventKafkaDto;
import io.github.marianciuc.streamingservice.subscription.dto.UserSubscriptionDto;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserSubscriptionServiceImplTest {

    private final UserSubscriptionRepository repository = mock(UserSubscriptionRepository.class);
    private final SubscriptionService subscriptionService = mock(SubscriptionService.class);
    private final OrderClient orderClient = mock(OrderClient.class);
    private final UserSubscriptionMapper mapper = mock(UserSubscriptionMapper.class);
    private final UserSubscriptionServiceImpl service =
            new UserSubscriptionServiceImpl(repository, subscriptionService, orderClient, mapper);

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
}
