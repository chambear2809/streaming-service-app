package io.github.marianciuc.streamingservice.billing.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateBillingLineItemRequest(
        @NotBlank String title,
        String description,
        @NotNull @Positive Integer quantity,
        @NotNull @DecimalMin(value = "0.00") BigDecimal unitAmount,
        LocalDate servicePeriodStart,
        LocalDate servicePeriodEnd
) {
}
