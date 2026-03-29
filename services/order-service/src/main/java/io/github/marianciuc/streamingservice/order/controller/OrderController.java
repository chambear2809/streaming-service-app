package io.github.marianciuc.streamingservice.order.controller;

import io.github.marianciuc.streamingservice.order.dto.OrderRequest;
import io.github.marianciuc.streamingservice.order.dto.OrderResponse;
import io.github.marianciuc.streamingservice.order.entity.Order;
import io.github.marianciuc.streamingservice.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@RequestBody OrderRequest orderRequest, Authentication authentication) {
        return ResponseEntity.ok(orderService.createOrder(orderRequest, authentication));
    }

    @PutMapping("/{id}")
    public ResponseEntity<OrderResponse> updatePlan(@PathVariable UUID id, @RequestBody OrderRequest orderRequest, Authentication authentication) {
        return ResponseEntity.ok(orderService.updateOrder(id, orderRequest, authentication));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable UUID id) {
        return ResponseEntity.ok(orderService.getOrderById(id));
    }

    @GetMapping
    public ResponseEntity<List<OrderResponse>> getOrders(
            @RequestParam(value = "userId", required = false) UUID userId,
            Authentication authentication
    ) {
        if (userId != null) {
            return ResponseEntity.ok(orderService.getOrdersByUserId(userId));
        }
        if (authentication != null) {
            return ResponseEntity.ok(orderService.getOrdersByAuthentication(authentication));
        }
        return ResponseEntity.ok(orderService.getAllOrders());
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('ROLE_SERVICE')")
    public ResponseEntity<Void> updateOrderStatus(@PathVariable UUID id, @RequestBody Order order) {
        orderService.updateOrderStatus(id, order.getOrderStatus());
        return ResponseEntity.ok().build();
    }
}
