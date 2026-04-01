package io.github.marianciuc.streamingservice.order.integration;

import io.github.marianciuc.streamingservice.order.client.SubscriptionClient;
import io.github.marianciuc.streamingservice.order.entity.Order;
import io.github.marianciuc.streamingservice.order.entity.OrderStatus;
import io.github.marianciuc.streamingservice.order.repository.OrderRepository;
import io.github.marianciuc.streamingservice.order.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.config.client.ConfigClientAutoConfiguration;
import org.springframework.cloud.netflix.eureka.EurekaClientAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = OrderStatusTransactionIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.application.name=order-service-tx-it",
                "spring.cloud.config.enabled=false",
                "eureka.client.enabled=false",
                "spring.flyway.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:order_status_tx_it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop"
        }
)
@DirtiesContext
class OrderStatusTransactionIntegrationTest {

    @Autowired
    private OrderServiceImpl orderService;

    @Autowired
    private OrderRepository orderRepository;

    @MockBean
    private SubscriptionClient subscriptionClient;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
    }

    @Test
    void updateOrderStatusKeepsPersistedStateWhenActivationFails() {
        Order order = orderRepository.save(Order.builder()
                .userId(UUID.randomUUID())
                .subscriptionId(UUID.randomUUID())
                .amount(BigDecimal.TEN)
                .orderCreateDate(LocalDateTime.now())
                .orderStatus(OrderStatus.CREATED)
                .build());
        when(subscriptionClient.activateOrder(any()))
                .thenThrow(new RuntimeException("subscription service unavailable"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> orderService.updateOrderStatus(order.getId(), OrderStatus.PAID)
        );

        assertEquals("Unable to activate the subscription for this order", exception.getMessage());

        Order persistedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.PAID, persistedOrder.getOrderStatus());
        assertNotNull(persistedOrder.getOrderCompletedDate());
        assertNull(persistedOrder.getSubscriptionActivatedAt());

        reset(subscriptionClient);
        when(subscriptionClient.activateOrder(any())).thenReturn(ResponseEntity.ok().build());

        orderService.updateOrderStatus(order.getId(), OrderStatus.PAID);

        Order retriedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.PAID, retriedOrder.getOrderStatus());
        assertNotNull(retriedOrder.getOrderCompletedDate());
        assertNotNull(retriedOrder.getSubscriptionActivatedAt());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            ConfigClientAutoConfiguration.class,
            EurekaClientAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            SecurityAutoConfiguration.class,
            UserDetailsServiceAutoConfiguration.class
    })
    @EntityScan(basePackageClasses = Order.class)
    @EnableJpaRepositories(basePackageClasses = OrderRepository.class)
    @Import(OrderServiceImpl.class)
    static class TestApplication {

        @Bean
        TransactionOperations transactionOperations(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }
    }
}
