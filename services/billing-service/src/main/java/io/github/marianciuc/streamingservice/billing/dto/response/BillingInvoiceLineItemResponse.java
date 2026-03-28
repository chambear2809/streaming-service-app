package io.github.marianciuc.streamingservice.billing.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BillingInvoiceLineItemResponse(
        UUID id,
        String title,
        String description,
        Integer quantity,
        BigDecimal unitAmount,
        BigDecimal lineTotal,
        LocalDate servicePeriodStart,
        LocalDate servicePeriodEnd
) {
}
