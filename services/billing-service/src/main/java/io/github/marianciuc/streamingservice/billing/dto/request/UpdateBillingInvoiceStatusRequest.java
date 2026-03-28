package io.github.marianciuc.streamingservice.billing.dto.request;

import io.github.marianciuc.streamingservice.billing.enums.InvoiceStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateBillingInvoiceStatusRequest(
        @NotNull InvoiceStatus status,
        String externalPaymentReference,
        String notes
) {
}
