package io.github.marianciuc.streamingservice.subscription.service.impl;

import io.github.marianciuc.streamingservice.subscription.entity.RecordStatus;
import io.github.marianciuc.streamingservice.subscription.entity.Subscription;
import io.github.marianciuc.streamingservice.subscription.exceptions.NotFoundException;
import io.github.marianciuc.streamingservice.subscription.mapper.SubscriptionMapper;
import io.github.marianciuc.streamingservice.subscription.repository.SubscriptionRepository;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SubscriptionServiceImplTest {

    private final SubscriptionRepository subscriptionRepositoryMock = mock(SubscriptionRepository.class);
    private final SubscriptionMapper subscriptionMapperMock = mock(SubscriptionMapper.class);
    private final SubscriptionServiceImpl subscriptionServiceImplUnderTest =
            new SubscriptionServiceImpl(subscriptionRepositoryMock, subscriptionMapperMock);

    private final Subscription subscription = new Subscription();

    @Test
    public void shouldDeleteSubscription() {
        UUID id = UUID.randomUUID();
        subscription.setRecordStatus(RecordStatus.ACTIVE);
        when(subscriptionRepositoryMock.findById(any(UUID.class))).thenReturn(Optional.of(subscription));
        
        subscriptionServiceImplUnderTest.deleteSubscription(id);
        
        Assertions.assertEquals(RecordStatus.DELETED, subscription.getRecordStatus());
        Mockito.verify(subscriptionRepositoryMock, Mockito.times(1)).save(any(Subscription.class));
    }

    @Test
    public void shouldThrowExceptionWhenSubscriptionNotFound() {
        UUID id = UUID.randomUUID();
        when(subscriptionRepositoryMock.findById(any(UUID.class))).thenReturn(Optional.empty());
        
        Assertions.assertThrows(NotFoundException.class, () -> subscriptionServiceImplUnderTest.deleteSubscription(id));
    }

    @Test
    public void shouldReturnOnlyActiveSubscriptions() {
        Subscription active = new Subscription();
        active.setId(UUID.randomUUID());
        active.setRecordStatus(RecordStatus.ACTIVE);
        Subscription deleted = new Subscription();
        deleted.setId(UUID.randomUUID());
        deleted.setRecordStatus(RecordStatus.DELETED);

        when(subscriptionRepositoryMock.findAll()).thenReturn(List.of(active, deleted));
        when(subscriptionMapperMock.toResponse(active)).thenReturn(Mockito.mock(io.github.marianciuc.streamingservice.subscription.dto.SubscriptionResponse.class));

        Assertions.assertEquals(1, subscriptionServiceImplUnderTest.getAllSubscriptions().size());
        Mockito.verify(subscriptionMapperMock, Mockito.times(1)).toResponse(active);
        Mockito.verify(subscriptionMapperMock, Mockito.never()).toResponse(deleted);
    }
}
