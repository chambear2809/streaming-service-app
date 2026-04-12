package io.github.marianciuc.streamingservice.user.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test for the demo auth controller health endpoint.
 *
 * Uses DemoUserApplication (the minimal demo-profile build: web-only, no DB/Kafka/Security).
 * Required secrets are supplied inline so no external config server is needed.
 */
@SpringBootTest(
        classes = DemoUserApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@TestPropertySource(properties = {
        "demo.auth.secret=test-secret-value",
        "demo.auth.password=test-password",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class DemoAuthControllerHealthTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointReturns200Ok() throws Exception {
        mockMvc.perform(get("/api/v1/demo/auth/health"))
                .andExpect(status().isOk());
    }
}
