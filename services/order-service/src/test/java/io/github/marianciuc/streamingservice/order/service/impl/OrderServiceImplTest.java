package io.github.marianciuc.streamingservice.order.service.impl;

import io.github.marianciuc.streamingservice.order.client.SubscriptionClient;
import io.github.marianciuc.streamingservice.order.dto.OrderActivationRequest;
import io.github.marianciuc.streamingservice.order.dto.OrderResponse;
import io.github.marianciuc.streamingservice.order.entity.Order;
import io.github.marianciuc.streamingservice.order.entity.OrderStatus;
import io.github.marianciuc.streamingservice.order.repository.OrderRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceImplTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final SubscriptionClient subscriptionClient = mock(SubscriptionClient.class);
    private final OrderServiceImpl service = new OrderServiceImpl(
            repository,
            subscriptionClient,
            TransactionOperations.withoutTransaction()
    );

    @Test
    void getOrdersByAuthenticationFallsBackToAllOrdersForAnonymousPrincipal() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn("anonymousUser");

        Order firstOrder = Order.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .subscriptionId(UUID.randomUUID())
                .amount(BigDecimal.TEN)
                .orderCreateDate(LocalDateTime.now())
                .orderStatus(OrderStatus.CREATED)
                .build();
        Order secondOrder = Order.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .subscriptionId(UUID.randomUUID())
                .amount(BigDecimal.ONE)
                .orderCreateDate(LocalDateTime.now())
                .orderStatus(OrderStatus.PAID)
                .build();
        when(repository.findAll()).thenReturn(List.of(firstOrder, secondOrder));

        List<OrderResponse> result = service.getOrdersByAuthentication(authentication);

        Assertions.assertEquals(2, result.size());
        Assertions.assertEquals(firstOrder.getId(), result.getFirst().id());
        Assertions.assertEquals(secondOrder.getId(), result.get(1).id());
    }

    @Test
    void updateOrderStatusDoesNotReactivateSubscriptionWhenCompletingPaidOrder() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .userId(UUID.randomUUID())
                .subscriptionId(UUID.randomUUID())
                .amount(BigDecimal.TEN)
                .orderCreateDate(LocalDateTime.now())
                .orderCompletedDate(LocalDateTime.now().minusDays(1))
                .orderStatus(OrderStatus.PAID)
                .subscriptionActivatedAt(LocalDateTime.now().minusDays(1))
                .build();
        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        when(repository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateOrderStatus(orderId, OrderStatus.COMPLETED);

        Assertions.assertEquals(OrderStatus.COMPLETED, order.getOrderStatus());
        verify(subscriptionClient, never()).activateOrder(any(OrderActivationRequest.class));
        verify(repository).saveAndFlush(order);
    }

    @Test
    void updateOrderStatusStagesOrderBeforeActivatingSubscription() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .userId(UUID.randomUUID())
                .subscriptionId(UUID.randomUUID())
                .amount(BigDecimal.TEN)
                .orderCreateDate(LocalDateTime.now())
                .orderStatus(OrderStatus.CREATED)
                .build();
        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        when(repository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateOrderStatus(orderId, OrderStatus.PAID);

        var inOrder = inOrder(repository, subscriptionClient);
        inOrder.verify(repository).saveAndFlush(order);
        inOrder.verify(subscriptionClient).activateOrder(any(OrderActivationRequest.class));
        inOrder.verify(repository).saveAndFlush(order);
        Assertions.assertNotNull(order.getSubscriptionActivatedAt());
    }

    @Test
    void updateOrderStatusRollsFailureUpAfterStagingOrderState() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .userId(UUID.randomUUID())
                .subscriptionId(UUID.randomUUID())
                .amount(BigDecimal.TEN)
                .orderCreateDate(LocalDateTime.now())
                .orderStatus(OrderStatus.CREATED)
                .build();
        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        when(repository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(subscriptionClient.activateOrder(any(OrderActivationRequest.class)))
                .thenThrow(new RuntimeException("subscription service unavailable"));

        IllegalStateException exception = Assertions.assertThrows(
                IllegalStateException.class,
                () -> service.updateOrderStatus(orderId, OrderStatus.PAID)
        );

        Assertions.assertTrue(exception.getMessage().contains("Unable to activate"));
        verify(repository).saveAndFlush(order);
    }

    @Test
    void updateOrderStatusRetriesActivationForSettledOrderWithoutActivationMarker() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .userId(UUID.randomUUID())
                .subscriptionId(UUID.randomUUID())
                .amount(BigDecimal.TEN)
                .orderCreateDate(LocalDateTime.now())
                .orderCompletedDate(LocalDateTime.now().minusMinutes(1))
                .orderStatus(OrderStatus.PAID)
                .build();
        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        when(repository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateOrderStatus(orderId, OrderStatus.PAID);

        verify(subscriptionClient).activateOrder(any(OrderActivationRequest.class));
        Assertions.assertNotNull(order.getSubscriptionActivatedAt());
    }

    @Test
    void updateOrderRejectsChangesForSettledOrder() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .userId(UUID.randomUUID())
                .subscriptionId(UUID.randomUUID())
                .amount(BigDecimal.TEN)
                .orderCreateDate(LocalDateTime.now())
                .orderStatus(OrderStatus.COMPLETED)
                .build();
        when(repository.findById(orderId)).thenReturn(Optional.of(order));

        IllegalArgumentException exception = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.updateOrder(
                        orderId,
                        new io.github.marianciuc.streamingservice.order.dto.OrderRequest(
                                order.getUserId(),
                                UUID.randomUUID(),
                                BigDecimal.ONE
                        ),
                        null
                )
        );

        Assertions.assertTrue(exception.getMessage().contains("cannot be changed"));
        verify(repository, never()).save(any(Order.class));
    }
}
