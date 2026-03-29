package io.github.marianciuc.streamingservice.billing.dto.response;

import io.github.marianciuc.streamingservice.billing.enums.BillingBusinessEventType;
import io.github.marianciuc.streamingservice.billing.enums.BillingCurrency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record BillingBusinessEventResponse(
        UUID id,
        UUID businessEventId,
        BillingBusinessEventType eventType,
        UUID userId,
        UUID invoiceId,
        UUID orderId,
        UUID subscriptionId,
        BillingCurrency currency,
        String title,
        String description,
        Integer quantity,
        BigDecimal unitAmount,
        BigDecimal taxAmount,
        BigDecimal discountAmount,
        LocalDate issuedDate,
        LocalDate dueDate,
        LocalDate servicePeriodStart,
        LocalDate servicePeriodEnd,
        String externalReference,
        String notes,
        UUID appliedInvoiceId,
        String appliedInvoiceNumber,
        String processingStatus,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
