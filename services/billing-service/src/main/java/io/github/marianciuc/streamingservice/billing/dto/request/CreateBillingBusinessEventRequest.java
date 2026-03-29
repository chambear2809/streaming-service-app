package io.github.marianciuc.streamingservice.billing.dto.request;

import io.github.marianciuc.streamingservice.billing.enums.BillingBusinessEventType;
import io.github.marianciuc.streamingservice.billing.enums.BillingCurrency;
import io.github.marianciuc.streamingservice.billing.enums.BillingCycle;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateBillingBusinessEventRequest(
        UUID eventId,
        @NotNull BillingBusinessEventType eventType,
        @NotNull UUID userId,
        UUID invoiceId,
        UUID orderId,
        UUID subscriptionId,
        BillingCycle billingCycle,
        @NotNull BillingCurrency currency,
        @NotBlank String title,
        String description,
        @NotNull @Positive Integer quantity,
        @NotNull @DecimalMin(value = "0.00") BigDecimal unitAmount,
        @DecimalMin(value = "0.00") BigDecimal taxAmount,
        @DecimalMin(value = "0.00") BigDecimal discountAmount,
        @NotNull LocalDate issuedDate,
        @NotNull LocalDate dueDate,
        LocalDate servicePeriodStart,
        LocalDate servicePeriodEnd,
        String externalReference,
        String notes
) {
}
