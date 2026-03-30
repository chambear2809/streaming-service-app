package io.github.marianciuc.streamingservice.billing.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.marianciuc.streamingservice.billing.dto.request.CreateBillingInvoiceRequest;
import io.github.marianciuc.streamingservice.billing.dto.request.CreateBillingLineItemRequest;
import io.github.marianciuc.streamingservice.billing.dto.request.UpdateBillingInvoiceStatusRequest;
import io.github.marianciuc.streamingservice.billing.dto.response.BillingAccountSummaryResponse;
import io.github.marianciuc.streamingservice.billing.dto.response.BillingInvoiceLineItemResponse;
import io.github.marianciuc.streamingservice.billing.dto.response.BillingInvoiceResponse;
import io.github.marianciuc.streamingservice.billing.enums.BillingCurrency;
import io.github.marianciuc.streamingservice.billing.enums.BillingCycle;
import io.github.marianciuc.streamingservice.billing.enums.InvoiceStatus;
import io.github.marianciuc.streamingservice.billing.service.BillingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BillingControllerTest {

    private final BillingService billingService = mock(BillingService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new BillingController(billingService),
                new BillingHealthController()
        ).build();
    }

    @Test
    void createInvoiceReturns201WithBody() throws Exception {
        UUID userId = UUID.randomUUID();
        BillingInvoiceResponse response = sampleInvoice(userId, InvoiceStatus.OPEN);
        when(billingService.createInvoice(any(CreateBillingInvoiceRequest.class))).thenReturn(response);

        CreateBillingInvoiceRequest request = new CreateBillingInvoiceRequest(
                userId, null, null,
                BillingCycle.MONTHLY, BillingCurrency.USD,
                LocalDate.now(), LocalDate.now().plusDays(14),
                null, null,
                new BigDecimal("1.50"), BigDecimal.ZERO, "test",
                List.of(new CreateBillingLineItemRequest("Feed", null, 1, new BigDecimal("25.00"), null, null))
        );

        mockMvc.perform(post("/api/v1/billing/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invoiceNumber", is("BILL-TEST-001")))
                .andExpect(jsonPath("$.status", is("OPEN")))
                .andExpect(jsonPath("$.lineItems", hasSize(1)));

        verify(billingService).createInvoice(any(CreateBillingInvoiceRequest.class));
    }

    @Test
    void createInvoiceRejects400WhenMissingRequiredFields() throws Exception {
        String emptyBody = "{}";

        mockMvc.perform(post("/api/v1/billing/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getInvoiceReturns200() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(billingService.getInvoice(invoiceId)).thenReturn(sampleInvoice(userId, InvoiceStatus.PAID));

        mockMvc.perform(get("/api/v1/billing/invoices/{invoiceId}", invoiceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PAID")))
                .andExpect(jsonPath("$.invoiceNumber", is("BILL-TEST-001")));

        verify(billingService).getInvoice(invoiceId);
    }

    @Test
    void listInvoicesPassesQueryParamsToService() throws Exception {
        UUID userId = UUID.randomUUID();
        when(billingService.listInvoices(eq(userId), eq(InvoiceStatus.OPEN), eq(true)))
                .thenReturn(List.of(sampleInvoice(userId, InvoiceStatus.OPEN)));

        mockMvc.perform(get("/api/v1/billing/invoices")
                        .param("userId", userId.toString())
                        .param("status", "OPEN")
                        .param("overdueOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status", is("OPEN")));

        verify(billingService).listInvoices(userId, InvoiceStatus.OPEN, true);
    }

    @Test
    void listInvoicesDefaultsOverdueOnlyToFalse() throws Exception {
        when(billingService.listInvoices(null, null, false)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/billing/invoices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        verify(billingService).listInvoices(null, null, false);
    }

    @Test
    void addLineItemReturns201() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(billingService.addLineItem(eq(invoiceId), any(CreateBillingLineItemRequest.class)))
                .thenReturn(sampleInvoice(userId, InvoiceStatus.OPEN));

        CreateBillingLineItemRequest request = new CreateBillingLineItemRequest(
                "Ad insertion", "Primary feed", 3, new BigDecimal("5.00"), null, null
        );

        mockMvc.perform(post("/api/v1/billing/invoices/{invoiceId}/line-items", invoiceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invoiceNumber", is("BILL-TEST-001")));

        verify(billingService).addLineItem(eq(invoiceId), any(CreateBillingLineItemRequest.class));
    }

    @Test
    void updateInvoiceStatusReturns200() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(billingService.updateInvoiceStatus(eq(invoiceId), any(UpdateBillingInvoiceStatusRequest.class)))
                .thenReturn(sampleInvoice(userId, InvoiceStatus.PAID));

        UpdateBillingInvoiceStatusRequest request = new UpdateBillingInvoiceStatusRequest(
                InvoiceStatus.PAID, "ext-ref-123", "payment received"
        );

        mockMvc.perform(put("/api/v1/billing/invoices/{invoiceId}/status", invoiceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PAID")));

        verify(billingService).updateInvoiceStatus(eq(invoiceId), any(UpdateBillingInvoiceStatusRequest.class));
    }

    @Test
    void getAccountSummaryReturns200() throws Exception {
        UUID userId = UUID.randomUUID();
        BillingAccountSummaryResponse summary = new BillingAccountSummaryResponse(
                userId, 5, 2, 1, 2,
                new BigDecimal("500.00"), new BigDecimal("200.00"), new BigDecimal("300.00"),
                LocalDate.now().plusDays(7), "USD"
        );
        when(billingService.getAccountSummary(userId)).thenReturn(summary);

        mockMvc.perform(get("/api/v1/billing/accounts/{userId}/summary", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceCount", is(5)))
                .andExpect(jsonPath("$.openInvoiceCount", is(2)))
                .andExpect(jsonPath("$.totalOutstanding", is(300.00)));

        verify(billingService).getAccountSummary(userId);
    }

    @Test
    void healthEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/api/v1/billing/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ok")));
    }

    private BillingInvoiceResponse sampleInvoice(UUID userId, InvoiceStatus status) {
        return new BillingInvoiceResponse(
                UUID.randomUUID(),
                "BILL-TEST-001",
                userId,
                null,
                null,
                status,
                BillingCycle.MONTHLY,
                BillingCurrency.USD,
                new BigDecimal("25.00"),
                new BigDecimal("1.50"),
                BigDecimal.ZERO,
                new BigDecimal("26.50"),
                status == InvoiceStatus.PAID ? BigDecimal.ZERO : new BigDecimal("26.50"),
                LocalDate.now(),
                LocalDate.now().plusDays(14),
                null,
                null,
                null,
                "test",
                LocalDateTime.now(),
                LocalDateTime.now(),
                List.of(new BillingInvoiceLineItemResponse(
                        UUID.randomUUID(), "Feed", null, 1,
                        new BigDecimal("25.00"), new BigDecimal("25.00"),
                        null, null
                ))
        );
    }
}
