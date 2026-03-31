package io.github.marianciuc.streamingservice.subscription.controller;

import io.github.marianciuc.streamingservice.subscription.config.SecurityConfig;
import io.github.marianciuc.streamingservice.subscription.dto.OrderCreationEventKafkaDto;
import io.github.marianciuc.streamingservice.subscription.dto.SubscriptionResponse;
import io.github.marianciuc.streamingservice.subscription.entity.RecordStatus;
import io.github.marianciuc.streamingservice.subscription.service.SubscriptionService;
import io.github.marianciuc.streamingservice.subscription.service.UserSubscriptionService;
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
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@Import({SecurityConfig.class, SubscriptionController.class})
@TestPropertySource(properties = {
        "internal.auth.secret=test-secret",
        "spring.cloud.config.enabled=false",
        "spring.cloud.config.import-check.enabled=false"
})
class SubscriptionControllerSecurityTest {

    private static final String AUTH_HEADER = "X-Streaming-Internal-Auth";
    private static final String AUTHORITIES_HEADER = "X-Streaming-Authorities";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SubscriptionService subscriptionService;

    @MockBean
    private UserSubscriptionService userSubscriptionService;

    @Test
    void getSubscriptionsRequiresInternalSecret() throws Exception {
        mockMvc.perform(get("/api/v1/subscription/all"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getSubscriptionsAllowsBillingCapability() throws Exception {
        when(subscriptionService.getAllSubscriptions()).thenReturn(List.of(subscriptionResponse()));

        mockMvc.perform(get("/api/v1/subscription/all")
                        .header(AUTH_HEADER, "test-secret")
                        .header(AUTHORITIES_HEADER, "CAP_BILLING"))
                .andExpect(status().isOk());
    }

    @Test
    void cancelSubscriptionRejectsServiceOnlyAccess() throws Exception {
        mockMvc.perform(post("/api/v1/subscription/cancel")
                        .param("id", UUID.randomUUID().toString())
                        .header(AUTH_HEADER, "test-secret"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cancelSubscriptionAllowsBillingWriteCapability() throws Exception {
        mockMvc.perform(post("/api/v1/subscription/cancel")
                        .param("id", UUID.randomUUID().toString())
                        .header(AUTH_HEADER, "test-secret")
                        .header(AUTHORITIES_HEADER, "CAP_BILLING_WRITE"))
                .andExpect(status().isOk());
    }

    @Test
    void activateOrderAllowsTrustedServiceRequests() throws Exception {
        mockMvc.perform(post("/api/v1/subscription/activate-order")
                        .header(AUTH_HEADER, "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orderId": "%s",
                                  "userId": "%s",
                                  "subscriptionId": "%s"
                                }
                                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isOk());
    }

    private static SubscriptionResponse subscriptionResponse() {
        return new SubscriptionResponse(
                UUID.randomUUID(),
                "Premium",
                "Premium plan",
                3,
                30,
                Set.of(),
                BigDecimal.valueOf(19.99),
                false,
                null,
                LocalDateTime.now(),
                RecordStatus.ACTIVE,
                LocalDateTime.now()
        );
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
