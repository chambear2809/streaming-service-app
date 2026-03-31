package io.github.marianciuc.streamingservice.order.controller;

import io.github.marianciuc.streamingservice.order.config.SecurityConfig;
import io.github.marianciuc.streamingservice.order.dto.OrderResponse;
import io.github.marianciuc.streamingservice.order.entity.OrderStatus;
import io.github.marianciuc.streamingservice.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@Import({SecurityConfig.class, OrderController.class})
@TestPropertySource(properties = {
        "internal.auth.secret=test-secret",
        "spring.cloud.config.enabled=false",
        "spring.cloud.config.import-check.enabled=false"
})
class OrderControllerSecurityTest {

    private static final String AUTH_HEADER = "X-Streaming-Internal-Auth";
    private static final String AUTHORITIES_HEADER = "X-Streaming-Authorities";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @Test
    void getOrdersRequiresInternalSecret() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getOrdersRejectsServiceOnlyAccess() throws Exception {
        mockMvc.perform(get("/api/v1/orders")
                        .header(AUTH_HEADER, "test-secret"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getOrdersAllowsBillingCapability() throws Exception {
        when(orderService.getOrdersByAuthentication(any()))
                .thenReturn(List.of(orderResponse()));

        mockMvc.perform(get("/api/v1/orders")
                        .header(AUTH_HEADER, "test-secret")
                        .header(AUTHORITIES_HEADER, "CAP_BILLING"))
                .andExpect(status().isOk());
    }

    @Test
    void createOrderAllowsBillingWriteCapability() throws Exception {
        when(orderService.createOrder(any(), any()))
                .thenReturn(orderResponse());

        mockMvc.perform(post("/api/v1/orders")
                        .header(AUTH_HEADER, "test-secret")
                        .header(AUTHORITIES_HEADER, "CAP_BILLING_WRITE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "%s",
                                  "subscriptionId": "%s",
                                  "price": 19.99
                                }
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isOk());
    }

    @Test
    void updateOrderStatusAllowsTrustedServiceRequests() throws Exception {
        mockMvc.perform(put("/api/v1/orders/{id}/status", UUID.randomUUID())
                        .header(AUTH_HEADER, "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orderStatus": "PAID"
                                }
                                """))
                .andExpect(status().isOk());
    }

    private static OrderResponse orderResponse() {
        return new OrderResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigDecimal.valueOf(19.99),
                UUID.randomUUID(),
                LocalDateTime.now(),
                OrderStatus.CREATED
        );
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
