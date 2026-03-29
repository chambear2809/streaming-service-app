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
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceImplTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final SubscriptionClient subscriptionClient = mock(SubscriptionClient.class);
    private final OrderServiceImpl service = new OrderServiceImpl(repository, subscriptionClient);

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
                .build();
        when(repository.findById(orderId)).thenReturn(Optional.of(order));
        when(repository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateOrderStatus(orderId, OrderStatus.COMPLETED);

        Assertions.assertEquals(OrderStatus.COMPLETED, order.getOrderStatus());
        verify(subscriptionClient, never()).activateOrder(any(OrderActivationRequest.class));
        verify(repository).save(order);
    }

    @Test
    void updateOrderStatusFailsBeforePersistingWhenActivationFails() {
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
        when(subscriptionClient.activateOrder(any(OrderActivationRequest.class)))
                .thenThrow(new RuntimeException("subscription service unavailable"));

        IllegalStateException exception = Assertions.assertThrows(
                IllegalStateException.class,
                () -> service.updateOrderStatus(orderId, OrderStatus.PAID)
        );

        Assertions.assertTrue(exception.getMessage().contains("Unable to activate"));
        verify(repository, never()).save(any(Order.class));
    }
}
