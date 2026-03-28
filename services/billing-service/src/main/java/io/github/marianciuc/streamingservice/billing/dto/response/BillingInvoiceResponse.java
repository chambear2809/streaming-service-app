package io.github.marianciuc.streamingservice.billing.dto.response;

import io.github.marianciuc.streamingservice.billing.enums.BillingCurrency;
import io.github.marianciuc.streamingservice.billing.enums.BillingCycle;
import io.github.marianciuc.streamingservice.billing.enums.InvoiceStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record BillingInvoiceResponse(
        UUID id,
        String invoiceNumber,
        UUID userId,
        UUID orderId,
        UUID subscriptionId,
        InvoiceStatus status,
        BillingCycle billingCycle,
        BillingCurrency currency,
        BigDecimal subtotalAmount,
        BigDecimal taxAmount,
        BigDecimal discountAmount,
        BigDecimal totalAmount,
        BigDecimal balanceDue,
        LocalDate issuedDate,
        LocalDate dueDate,
        LocalDate servicePeriodStart,
        LocalDate servicePeriodEnd,
        String externalPaymentReference,
        String notes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<BillingInvoiceLineItemResponse> lineItems
) {
}
