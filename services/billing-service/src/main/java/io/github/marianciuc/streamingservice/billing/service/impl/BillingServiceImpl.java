package io.github.marianciuc.streamingservice.billing.service.impl;

import io.github.marianciuc.streamingservice.billing.dto.request.CreateBillingInvoiceRequest;
import io.github.marianciuc.streamingservice.billing.dto.request.CreateBillingLineItemRequest;
import io.github.marianciuc.streamingservice.billing.dto.request.UpdateBillingInvoiceStatusRequest;
import io.github.marianciuc.streamingservice.billing.dto.response.BillingAccountSummaryResponse;
import io.github.marianciuc.streamingservice.billing.dto.response.BillingInvoiceLineItemResponse;
import io.github.marianciuc.streamingservice.billing.dto.response.BillingInvoiceResponse;
import io.github.marianciuc.streamingservice.billing.entity.BillingInvoice;
import io.github.marianciuc.streamingservice.billing.entity.BillingInvoiceLineItem;
import io.github.marianciuc.streamingservice.billing.enums.InvoiceStatus;
import io.github.marianciuc.streamingservice.billing.exception.BillingNotFoundException;
import io.github.marianciuc.streamingservice.billing.exception.InvalidBillingStateException;
import io.github.marianciuc.streamingservice.billing.repository.BillingInvoiceRepository;
import io.github.marianciuc.streamingservice.billing.service.BillingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BillingServiceImpl implements BillingService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final BillingInvoiceRepository billingInvoiceRepository;

    @Override
    @Transactional
    public BillingInvoiceResponse createInvoice(CreateBillingInvoiceRequest request) {
        validateInvoiceDates(request.issuedDate(), request.dueDate());
        validateServiceWindow(request.servicePeriodStart(), request.servicePeriodEnd());

        BillingInvoice invoice = BillingInvoice.builder()
                .invoiceNumber(generateInvoiceNumber())
                .userId(request.userId())
                .orderId(request.orderId())
                .subscriptionId(request.subscriptionId())
                .status(InvoiceStatus.OPEN)
                .billingCycle(request.billingCycle())
                .currency(request.currency())
                .subtotalAmount(ZERO)
                .taxAmount(normalizeAmount(request.taxAmount()))
                .discountAmount(normalizeAmount(request.discountAmount()))
                .totalAmount(ZERO)
                .balanceDue(ZERO)
                .issuedDate(request.issuedDate())
                .dueDate(request.dueDate())
                .servicePeriodStart(request.servicePeriodStart())
                .servicePeriodEnd(request.servicePeriodEnd())
                .notes(normalizeText(request.notes()))
                .build();

        for (CreateBillingLineItemRequest lineItemRequest : request.lineItems()) {
            validateServiceWindow(lineItemRequest.servicePeriodStart(), lineItemRequest.servicePeriodEnd());
            invoice.addLineItem(toLineItem(lineItemRequest));
        }

        refreshDerivedAmounts(invoice);
        billingInvoiceRepository.save(invoice);
        return toResponse(invoice);
    }

    @Override
    @Transactional(readOnly = true)
    public BillingInvoiceResponse getInvoice(UUID invoiceId) {
        return toResponse(getManagedInvoice(invoiceId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BillingInvoiceResponse> listInvoices(UUID userId, InvoiceStatus status, boolean overdueOnly) {
        List<BillingInvoice> invoices = userId == null
                ? billingInvoiceRepository.findAllDetailedOrderByCreatedAtDesc()
                : billingInvoiceRepository.findAllDetailedByUserIdOrderByCreatedAtDesc(userId);

        return invoices.stream()
                .filter(invoice -> status == null || resolveStatus(invoice) == status)
                .filter(invoice -> !overdueOnly || resolveStatus(invoice) == InvoiceStatus.PAST_DUE)
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public BillingInvoiceResponse addLineItem(UUID invoiceId, CreateBillingLineItemRequest request) {
        BillingInvoice invoice = getManagedInvoice(invoiceId);
        ensureEditable(invoice);
        validateServiceWindow(request.servicePeriodStart(), request.servicePeriodEnd());

        invoice.addLineItem(toLineItem(request));
        refreshDerivedAmounts(invoice);
        return toResponse(invoice);
    }

    @Override
    @Transactional
    public BillingInvoiceResponse updateInvoiceStatus(UUID invoiceId, UpdateBillingInvoiceStatusRequest request) {
        BillingInvoice invoice = getManagedInvoice(invoiceId);
        InvoiceStatus targetStatus = request.status();

        if (targetStatus == InvoiceStatus.PAST_DUE) {
            throw new InvalidBillingStateException("Past-due status is derived from the due date and cannot be set directly.");
        }

        if ((invoice.getStatus() == InvoiceStatus.PAID || invoice.getStatus() == InvoiceStatus.VOID)
                && targetStatus != invoice.getStatus()) {
            throw new InvalidBillingStateException("Paid or void invoices cannot transition to another status.");
        }

        invoice.setStatus(targetStatus);
        if (request.externalPaymentReference() != null && !request.externalPaymentReference().isBlank()) {
            invoice.setExternalPaymentReference(request.externalPaymentReference().trim());
        }
        if (request.notes() != null) {
            invoice.setNotes(normalizeText(request.notes()));
        }

        refreshDerivedAmounts(invoice);
        return toResponse(invoice);
    }

    @Override
    @Transactional(readOnly = true)
    public BillingAccountSummaryResponse getAccountSummary(UUID userId) {
        List<BillingInvoice> invoices = billingInvoiceRepository.findAllDetailedByUserIdOrderByCreatedAtDesc(userId);

        BigDecimal totalInvoiced = invoices.stream()
                .map(BillingInvoice::getTotalAmount)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal totalPaid = invoices.stream()
                .filter(invoice -> resolveStatus(invoice) == InvoiceStatus.PAID)
                .map(BillingInvoice::getTotalAmount)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal totalOutstanding = invoices.stream()
                .map(BillingInvoice::getBalanceDue)
                .reduce(ZERO, BigDecimal::add);
        LocalDate nextDueDate = invoices.stream()
                .filter(invoice -> invoice.getBalanceDue().compareTo(ZERO) > 0)
                .filter(invoice -> {
                    InvoiceStatus status = resolveStatus(invoice);
                    return status != InvoiceStatus.PAID && status != InvoiceStatus.VOID;
                })
                .map(BillingInvoice::getDueDate)
                .min(Comparator.naturalOrder())
                .orElse(null);
        String currency = invoices.stream()
                .map(invoice -> invoice.getCurrency().name())
                .distinct()
                .reduce((left, right) -> "MIXED")
                .orElse("N/A");

        return new BillingAccountSummaryResponse(
                userId,
                invoices.size(),
                invoices.stream().filter(invoice -> resolveStatus(invoice) == InvoiceStatus.OPEN).count(),
                invoices.stream().filter(invoice -> resolveStatus(invoice) == InvoiceStatus.PAST_DUE).count(),
                invoices.stream().filter(invoice -> resolveStatus(invoice) == InvoiceStatus.PAID).count(),
                scale(totalInvoiced),
                scale(totalPaid),
                scale(totalOutstanding),
                nextDueDate,
                currency
        );
    }

    private BillingInvoice getManagedInvoice(UUID invoiceId) {
        return billingInvoiceRepository.findDetailedById(invoiceId)
                .orElseThrow(() -> new BillingNotFoundException("Billing invoice %s was not found.".formatted(invoiceId)));
    }

    private BillingInvoiceLineItem toLineItem(CreateBillingLineItemRequest request) {
        BigDecimal unitAmount = normalizeAmount(request.unitAmount());
        BigDecimal lineTotal = scale(unitAmount.multiply(BigDecimal.valueOf(request.quantity())));

        return BillingInvoiceLineItem.builder()
                .title(request.title().trim())
                .description(normalizeText(request.description()))
                .quantity(request.quantity())
                .unitAmount(unitAmount)
                .lineTotal(lineTotal)
                .servicePeriodStart(request.servicePeriodStart())
                .servicePeriodEnd(request.servicePeriodEnd())
                .build();
    }

    private void refreshDerivedAmounts(BillingInvoice invoice) {
        for (BillingInvoiceLineItem lineItem : invoice.getLineItems()) {
            BigDecimal normalizedUnitAmount = normalizeAmount(lineItem.getUnitAmount());
            lineItem.setUnitAmount(normalizedUnitAmount);
            lineItem.setLineTotal(scale(normalizedUnitAmount.multiply(BigDecimal.valueOf(lineItem.getQuantity()))));
        }

        synchronizeStatus(invoice);

        BigDecimal subtotal = invoice.getLineItems().stream()
                .map(BillingInvoiceLineItem::getLineTotal)
                .reduce(ZERO, BigDecimal::add);

        BigDecimal tax = normalizeAmount(invoice.getTaxAmount());
        BigDecimal discount = normalizeAmount(invoice.getDiscountAmount());
        BigDecimal total = subtotal.add(tax).subtract(discount);
        if (total.compareTo(ZERO) < 0) {
            total = ZERO;
        }

        invoice.setSubtotalAmount(scale(subtotal));
        invoice.setTaxAmount(tax);
        invoice.setDiscountAmount(discount);
        invoice.setTotalAmount(scale(total));

        InvoiceStatus effectiveStatus = resolveStatus(invoice);
        if (effectiveStatus == InvoiceStatus.PAID || effectiveStatus == InvoiceStatus.VOID) {
            invoice.setBalanceDue(ZERO);
        } else {
            invoice.setBalanceDue(scale(total));
        }
    }

    private void ensureEditable(BillingInvoice invoice) {
        InvoiceStatus effectiveStatus = resolveStatus(invoice);
        if (effectiveStatus == InvoiceStatus.PAID || effectiveStatus == InvoiceStatus.VOID) {
            throw new InvalidBillingStateException("Line items cannot be changed after an invoice is paid or voided.");
        }
    }

    private void synchronizeStatus(BillingInvoice invoice) {
        invoice.setStatus(resolveStatus(invoice));
    }

    private InvoiceStatus resolveStatus(BillingInvoice invoice) {
        InvoiceStatus status = invoice.getStatus();
        if (status == null || status == InvoiceStatus.PAID || status == InvoiceStatus.VOID || status == InvoiceStatus.DRAFT || invoice.getDueDate() == null) {
            return status;
        }
        if (invoice.getDueDate().isBefore(LocalDate.now())) {
            return InvoiceStatus.PAST_DUE;
        }
        if (status == InvoiceStatus.PAST_DUE) {
            return InvoiceStatus.OPEN;
        }
        return status;
    }

    private void validateServiceWindow(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw new InvalidBillingStateException("Service period end cannot be before service period start.");
        }
    }

    private void validateInvoiceDates(LocalDate issuedDate, LocalDate dueDate) {
        if (dueDate.isBefore(issuedDate)) {
            throw new InvalidBillingStateException("Invoice due date cannot be before the issued date.");
        }
    }

    private String generateInvoiceNumber() {
        String datePrefix = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        for (int attempt = 0; attempt < 10; attempt++) {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
            String invoiceNumber = "BILL-" + datePrefix + "-" + suffix;
            if (!billingInvoiceRepository.existsByInvoiceNumber(invoiceNumber)) {
                return invoiceNumber;
            }
        }
        throw new InvalidBillingStateException("Unable to allocate a unique invoice number.");
    }

    private BigDecimal normalizeAmount(BigDecimal amount) {
        return scale(amount == null ? ZERO : amount);
    }

    private BigDecimal scale(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private BillingInvoiceResponse toResponse(BillingInvoice invoice) {
        InvoiceStatus effectiveStatus = resolveStatus(invoice);
        return new BillingInvoiceResponse(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getUserId(),
                invoice.getOrderId(),
                invoice.getSubscriptionId(),
                effectiveStatus,
                invoice.getBillingCycle(),
                invoice.getCurrency(),
                scale(invoice.getSubtotalAmount()),
                scale(invoice.getTaxAmount()),
                scale(invoice.getDiscountAmount()),
                scale(invoice.getTotalAmount()),
                scale(invoice.getBalanceDue()),
                invoice.getIssuedDate(),
                invoice.getDueDate(),
                invoice.getServicePeriodStart(),
                invoice.getServicePeriodEnd(),
                invoice.getExternalPaymentReference(),
                invoice.getNotes(),
                invoice.getCreatedAt(),
                invoice.getUpdatedAt(),
                invoice.getLineItems().stream().map(this::toResponse).toList()
        );
    }

    private BillingInvoiceLineItemResponse toResponse(BillingInvoiceLineItem lineItem) {
        return new BillingInvoiceLineItemResponse(
                lineItem.getId(),
                lineItem.getTitle(),
                lineItem.getDescription(),
                lineItem.getQuantity(),
                scale(lineItem.getUnitAmount()),
                scale(lineItem.getLineTotal()),
                lineItem.getServicePeriodStart(),
                lineItem.getServicePeriodEnd()
        );
    }
}
