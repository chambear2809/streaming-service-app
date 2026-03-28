package io.github.marianciuc.streamingservice.billing.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BillingAccountSummaryResponse(
        UUID userId,
        long invoiceCount,
        long openInvoiceCount,
        long pastDueInvoiceCount,
        long paidInvoiceCount,
        BigDecimal totalInvoiced,
        BigDecimal totalPaid,
        BigDecimal totalOutstanding,
        LocalDate nextDueDate,
        String currency
) {
}
