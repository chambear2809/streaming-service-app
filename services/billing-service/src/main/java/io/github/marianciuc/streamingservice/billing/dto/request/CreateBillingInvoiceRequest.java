package io.github.marianciuc.streamingservice.billing.dto.request;

import io.github.marianciuc.streamingservice.billing.enums.BillingCurrency;
import io.github.marianciuc.streamingservice.billing.enums.BillingCycle;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateBillingInvoiceRequest(
        @NotNull UUID userId,
        UUID orderId,
        UUID subscriptionId,
        @NotNull BillingCycle billingCycle,
        @NotNull BillingCurrency currency,
        @NotNull LocalDate issuedDate,
        @NotNull LocalDate dueDate,
        LocalDate servicePeriodStart,
        LocalDate servicePeriodEnd,
        @DecimalMin(value = "0.00") BigDecimal taxAmount,
        @DecimalMin(value = "0.00") BigDecimal discountAmount,
        String notes,
        @NotEmpty List<@Valid CreateBillingLineItemRequest> lineItems
) {
}
