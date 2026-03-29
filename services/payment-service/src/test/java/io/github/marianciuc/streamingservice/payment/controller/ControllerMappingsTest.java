package io.github.marianciuc.streamingservice.payment.controller;

import io.github.marianciuc.streamingservice.payment.service.RefundService;
import io.github.marianciuc.streamingservice.payment.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ControllerMappingsTest {

    private final TransactionService transactionService = mock(TransactionService.class);
    private final RefundService refundService = mock(RefundService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new TransactionsController(transactionService),
                new RefundController(refundService)
        ).build();
    }

    @Test
    void exposesPaymentRoutesUnderExpectedBasePaths() throws Exception {
        when(transactionService.getTransactions(0, 10, "asc", null, null)).thenReturn(List.of());
        doNothing().when(refundService).processRefund(UUID.fromString("11111111-1111-1111-1111-111111111111"));

        mockMvc.perform(get("/api/v1/payments/transactions")
                        .param("page", "0")
                        .param("size", "10")
                        .param("sort", "asc"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/payments/refund/{transactionId}", "11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/"))
                .andExpect(status().isNotFound());
    }
}
