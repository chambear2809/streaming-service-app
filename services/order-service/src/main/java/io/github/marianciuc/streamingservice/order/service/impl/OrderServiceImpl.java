/*
 * Copyright (c) 2024  Vladimir Marianciuc. All Rights Reserved.
 *
 * Project: STREAMING SERVICE APP
 * File: OrderServiceImpl.java
 *
 */

package io.github.marianciuc.streamingservice.order.service.impl;

import io.github.marianciuc.streamingservice.order.client.SubscriptionClient;
import io.github.marianciuc.streamingservice.order.dto.*;
import io.github.marianciuc.streamingservice.order.entity.Order;
import io.github.marianciuc.streamingservice.order.entity.OrderStatus;
import io.github.marianciuc.streamingservice.order.repository.OrderRepository;
import io.github.marianciuc.streamingservice.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Implementation of the OrderService.
 * @author Vladimir Marianciuc
 */
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository repository;
    private final SubscriptionClient subscriptionClient;

    /**
     * Creates a new order based on the provided order request and authentication details.
     *
     * @param orderRequest    the OrderRequest containing details of the order to be created.
     * @param authentication  the Authentication object containing the user's authentication details.
     * @return the created OrderResponse.
     * @throws RuntimeException if there is an issue with the subscription.
     */
//    @Override
//    public OrderResponse createOrder(OrderRequest orderRequest) {
////        ResponseEntity<SubscriptionDto> subscriptionResponse = subscriptionClient.getSubscription(orderRequest.subscriptionId());
////        ResponseEntity<UserSubscriptionDto> activeSubscriptionResponse = subscriptionClient.getActiveSubscription(userId);
////
////        if (subscriptionResponse.getStatusCode().is2xxSuccessful()) {
////            this.cancelExistingOrders(userId);
////            if (activeSubscriptionResponse.getStatusCode().is4xxClientError()) {
////                return this.createNewOrder(subscriptionResponse.getBody(), userId, orderRequest.subscriptionId(), OrderStatus.CREATED, null);
////            } else if (activeSubscriptionResponse.getStatusCode().is2xxSuccessful() && activeSubscriptionResponse.getBody() != null) {
////                UUID activeSubscriptionId = activeSubscriptionResponse.getBody().id();
////                OrderResponse orderResponse = this.createNewOrder(subscriptionResponse.getBody(), userId, orderRequest.subscriptionId(), OrderStatus.SCHEDULED, activeSubscriptionResponse.getBody().endDate());
////                subscriptionClient.cancelSubscription(activeSubscriptionId);
////                return orderResponse;
////            }
////        }
//
//        throw new RuntimeException("Unable to create order due to subscription issues");
//    }

    /**
     * Resolves the user ID based on the authentication details and request.
     *
     * @param authentication the Authentication object containing the user's authentication details.
     * @param orderRequest   the OrderRequest containing details of the order to be created.
     * @return the resolved user ID.
     */
    private UUID resolveUserId(Authentication authentication, OrderRequest orderRequest) {
        if (authentication != null) {
            UUID authenticatedUserId = extractUserId(authentication.getPrincipal());
            if (authenticatedUserId != null) {
                return authenticatedUserId;
            }
        }
        if (orderRequest != null && orderRequest.userId() != null) {
            return orderRequest.userId();
        }
        throw new IllegalArgumentException("User ID is required");
    }

    private UUID extractUserId(Object principal) {
        if (principal == null) {
            return null;
        }

        for (String accessorName : List.of("getUserId", "getId")) {
            try {
                Method accessor = principal.getClass().getMethod(accessorName);
                Object value = accessor.invoke(principal);
                if (value instanceof UUID userId) {
                    return userId;
                }
            } catch (ReflectiveOperationException ignored) {
                // Try the next common accessor used by the security principal.
            }
        }

        return null;
    }

    /**
     * Cancels any existing orders for the user that are in the CREATED status.
     *
     * @param userId the ID of the user whose orders are to be cancelled.
     */
    private void cancelExistingOrders(UUID userId) {
        List<Order> orders = repository.findAllByUserIdAndOrderStatus(userId, OrderStatus.CREATED);
        orders.forEach(order -> order.setOrderStatus(OrderStatus.CANCELLED));
        repository.saveAll(orders);
    }

    /**
     * Creates a new order.
     *
     * @param subscriptionDto the SubscriptionDto containing subscription details.
     * @param userId          the ID of the user.
     * @param subscriptionId  the ID of the subscription.
     * @param orderStatus     the status of the order.
     * @param scheduledTime   the scheduled time for the order.
     * @return the created OrderResponse.
     */
    private OrderResponse toResponse(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getAmount(),
                order.getSubscriptionId(),
                order.getOrderCreateDate(),
                order.getOrderStatus()
        );
    }

    private Order getOrder(UUID orderId) {
        return repository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));
    }

    private void validateOrderRequest(OrderRequest orderRequest) {
        if (orderRequest == null) {
            throw new IllegalArgumentException("Order request is required");
        }
        if (orderRequest.price() == null || orderRequest.price().signum() <= 0) {
            throw new IllegalArgumentException("Order price must be greater than zero");
        }
    }

    @Override
    public OrderResponse createOrder(OrderRequest orderRequest, Authentication authentication) {
        validateOrderRequest(orderRequest);
        UUID userId = resolveUserId(authentication, orderRequest);

        Order order = Order.builder()
                .userId(userId)
                .subscriptionId(orderRequest.subscriptionId())
                .amount(orderRequest.price())
                .orderCreateDate(LocalDateTime.now())
                .orderStatus(OrderStatus.CREATED)
                .build();

        return toResponse(repository.save(order));
    }

    @Override
    public OrderResponse updateOrder(UUID orderId, OrderRequest orderRequest, Authentication authentication) {
        validateOrderRequest(orderRequest);
        Order order = getOrder(orderId);

        order.setUserId(resolveUserId(authentication, orderRequest));
        order.setSubscriptionId(orderRequest.subscriptionId());
        order.setAmount(orderRequest.price());
        if (order.getOrderCreateDate() == null) {
            order.setOrderCreateDate(LocalDateTime.now());
        }
        if (order.getOrderStatus() == null) {
            order.setOrderStatus(OrderStatus.CREATED);
        }

        return toResponse(repository.save(order));
    }

    @Override
    public OrderResponse getOrderById(UUID orderId) {
        return toResponse(getOrder(orderId));
    }

    @Override
    public List<OrderResponse> getOrdersByUserId(UUID customerId) {
        return repository.findAll().stream()
                .filter(order -> customerId.equals(order.getUserId()))
                .map(this::toResponse)
                .toList();
    }

    @Override
    public List<OrderResponse> getAllOrders() {
        return repository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public List<OrderResponse> getOrdersByAuthentication(Authentication authentication) {
        UUID userId = extractUserId(authentication == null ? null : authentication.getPrincipal());
        if (userId == null) {
            return getAllOrders();
        }
        return getOrdersByUserId(userId);
    }

    @Override
    public void updateOrderStatus(UUID orderId, OrderStatus orderStatus) {
        Order order = getOrder(orderId);
        OrderStatus previousStatus = order.getOrderStatus();
        boolean activatesSubscription = entersSettledState(previousStatus, orderStatus);

        if (activatesSubscription) {
            try {
                subscriptionClient.activateOrder(new OrderActivationRequest(
                        order.getId(),
                        order.getUserId(),
                        order.getSubscriptionId()
                ));
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Unable to activate the subscription for this order", exception);
            }
        }

        order.setOrderStatus(orderStatus);
        if (orderStatus == OrderStatus.PAID || orderStatus == OrderStatus.COMPLETED) {
            if (order.getOrderCompletedDate() == null) {
                order.setOrderCompletedDate(LocalDateTime.now());
            }
        }
        repository.save(order);
    }

    private boolean entersSettledState(OrderStatus previousStatus, OrderStatus nextStatus) {
        boolean nextSettled = nextStatus == OrderStatus.PAID || nextStatus == OrderStatus.COMPLETED;
        boolean previousSettled = previousStatus == OrderStatus.PAID || previousStatus == OrderStatus.COMPLETED;
        return nextSettled && !previousSettled;
    }
}
