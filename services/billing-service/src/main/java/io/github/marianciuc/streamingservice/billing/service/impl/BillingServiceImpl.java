package io.github.marianciuc.streamingservice.billing.service.impl;

import io.github.marianciuc.streamingservice.billing.dto.request.CreateBillingInvoiceRequest;
import io.github.marianciuc.streamingservice.billing.dto.request.CreateBillingLineItemRequest;
import io.github.marianciuc.streamingservice.billing.dto.request.CreateBillingBusinessEventRequest;
import io.github.marianciuc.streamingservice.billing.dto.request.UpdateBillingInvoiceStatusRequest;
import io.github.marianciuc.streamingservice.billing.dto.response.BillingAccountSummaryResponse;
import io.github.marianciuc.streamingservice.billing.dto.response.BillingBusinessEventResponse;
import io.github.marianciuc.streamingservice.billing.dto.response.BillingInvoiceLineItemResponse;
import io.github.marianciuc.streamingservice.billing.dto.response.BillingInvoiceResponse;
import io.github.marianciuc.streamingservice.billing.entity.BillingBusinessEvent;
import io.github.marianciuc.streamingservice.billing.entity.BillingInvoice;
import io.github.marianciuc.streamingservice.billing.entity.BillingInvoiceLineItem;
import io.github.marianciuc.streamingservice.billing.enums.BillingBusinessEventType;
import io.github.marianciuc.streamingservice.billing.enums.InvoiceStatus;
import io.github.marianciuc.streamingservice.billing.exception.BillingNotFoundException;
import io.github.marianciuc.streamingservice.billing.exception.InvalidBillingStateException;
import io.github.marianciuc.streamingservice.billing.repository.BillingBusinessEventRepository;
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
    private final BillingBusinessEventRepository billingBusinessEventRepository;

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

    @Override
    @Transactional
    public BillingBusinessEventResponse recordBusinessEvent(CreateBillingBusinessEventRequest request) {
        UUID businessEventId = request.eventId() == null ? UUID.randomUUID() : request.eventId();
        if (billingBusinessEventRepository.existsByBusinessEventId(businessEventId)) {
            return billingBusinessEventRepository.findByBusinessEventId(businessEventId)
                    .map(this::toResponse)
                    .orElseThrow(() -> new InvalidBillingStateException("Business event already exists but could not be loaded."));
        }

        BillingBusinessEvent event = BillingBusinessEvent.builder()
                .businessEventId(businessEventId)
                .eventType(request.eventType())
                .userId(request.userId())
                .invoiceId(request.invoiceId())
                .orderId(request.orderId())
                .subscriptionId(request.subscriptionId())
                .currency(request.currency())
                .title(request.title().trim())
                .description(normalizeText(request.description()))
                .quantity(request.quantity())
                .unitAmount(normalizeAmount(request.unitAmount()))
                .taxAmount(normalizeAmount(request.taxAmount()))
                .discountAmount(normalizeAmount(request.discountAmount()))
                .issuedDate(request.issuedDate())
                .dueDate(request.dueDate())
                .servicePeriodStart(request.servicePeriodStart())
                .servicePeriodEnd(request.servicePeriodEnd())
                .externalReference(normalizeText(request.externalReference()))
                .notes(normalizeText(request.notes()))
                .processingStatus("RECORDED")
                .build();

        BillingInvoice appliedInvoice = applyBusinessEvent(request);
        if (appliedInvoice != null) {
            event.setAppliedInvoiceId(appliedInvoice.getId());
            event.setAppliedInvoiceNumber(appliedInvoice.getInvoiceNumber());
            event.setProcessingStatus("APPLIED");
        }

        billingBusinessEventRepository.save(event);
        return toResponse(event);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BillingBusinessEventResponse> listBusinessEvents(UUID userId, BillingBusinessEventType eventType) {
        List<BillingBusinessEvent> events;
        if (userId != null) {
            events = billingBusinessEventRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
        } else if (eventType != null) {
            events = billingBusinessEventRepository.findAllByEventTypeOrderByCreatedAtDesc(eventType);
        } else {
            events = billingBusinessEventRepository.findAllByOrderByCreatedAtDesc();
        }

        return events.stream()
                .filter(event -> eventType == null || event.getEventType() == eventType)
                .map(this::toResponse)
                .toList();
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

    private BillingInvoice applyBusinessEvent(CreateBillingBusinessEventRequest request) {
        return switch (request.eventType()) {
            case ORDER_BOOKED, SUBSCRIPTION_RENEWED -> createInvoiceFromBusinessEvent(request);
            case PAYMENT_CAPTURED -> applyPaymentCapture(request);
            case PAYMENT_FAILED -> appendInvoiceNote(request, "Payment failed");
            case RETRY_SCHEDULED -> appendInvoiceNote(request, "Retry scheduled");
            case RECONCILIATION_RECORDED -> appendInvoiceNote(request, "Reconciliation recorded");
        };
    }

    private BillingInvoice createInvoiceFromBusinessEvent(CreateBillingBusinessEventRequest request) {
        BillingInvoiceResponse response = createInvoice(new CreateBillingInvoiceRequest(
                request.userId(),
                request.orderId(),
                request.subscriptionId(),
                request.billingCycle() == null ? io.github.marianciuc.streamingservice.billing.enums.BillingCycle.ONE_TIME : request.billingCycle(),
                request.currency(),
                request.issuedDate(),
                request.dueDate(),
                request.servicePeriodStart(),
                request.servicePeriodEnd(),
                request.taxAmount(),
                request.discountAmount(),
                request.notes(),
                List.of(new CreateBillingLineItemRequest(
                        request.title(),
                        request.description(),
                        request.quantity(),
                        request.unitAmount(),
                        request.servicePeriodStart(),
                        request.servicePeriodEnd()
                ))
        ));
        return getManagedInvoice(response.id());
    }

    private BillingInvoice applyPaymentCapture(CreateBillingBusinessEventRequest request) {
        BillingInvoice invoice = resolveInvoiceForEvent(request);
        invoice.setStatus(InvoiceStatus.PAID);
        if (request.externalReference() != null && !request.externalReference().isBlank()) {
            invoice.setExternalPaymentReference(request.externalReference().trim());
        }
        if (request.notes() != null) {
            invoice.setNotes(appendNote(invoice.getNotes(), request.notes()));
        }
        refreshDerivedAmounts(invoice);
        return invoice;
    }

    private BillingInvoice appendInvoiceNote(CreateBillingBusinessEventRequest request, String eventLabel) {
        BillingInvoice invoice = resolveInvoiceForEvent(request);
        invoice.setNotes(appendNote(invoice.getNotes(), eventLabel + ": " + request.title()));
        refreshDerivedAmounts(invoice);
        return invoice;
    }

    private BillingInvoice resolveInvoiceForEvent(CreateBillingBusinessEventRequest request) {
        if (request.invoiceId() != null) {
            return getManagedInvoice(request.invoiceId());
        }

        if (request.orderId() != null) {
            return billingInvoiceRepository.findAllDetailedByOrderIdOrderByCreatedAtDesc(request.orderId()).stream()
                    .findFirst()
                    .orElseThrow(() -> new BillingNotFoundException("Billing invoice for order %s was not found.".formatted(request.orderId())));
        }

        if (request.subscriptionId() != null) {
            return billingInvoiceRepository.findAllDetailedBySubscriptionIdOrderByCreatedAtDesc(request.subscriptionId()).stream()
                    .findFirst()
                    .orElseThrow(() -> new BillingNotFoundException("Billing invoice for subscription %s was not found.".formatted(request.subscriptionId())));
        }

        return billingInvoiceRepository.findAllDetailedByUserIdOrderByCreatedAtDesc(request.userId()).stream()
                .findFirst()
                .orElseThrow(() -> new BillingNotFoundException("Billing invoice for account %s was not found.".formatted(request.userId())));
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

    private String appendNote(String existingNotes, String nextNote) {
        String normalizedNext = normalizeText(nextNote);
        if (normalizedNext == null) {
            return normalizeText(existingNotes);
        }
        String normalizedExisting = normalizeText(existingNotes);
        return normalizedExisting == null ? normalizedNext : normalizedExisting + "\n" + normalizedNext;
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

    private BillingBusinessEventResponse toResponse(BillingBusinessEvent event) {
        return new BillingBusinessEventResponse(
                event.getId(),
                event.getBusinessEventId(),
                event.getEventType(),
                event.getUserId(),
                event.getInvoiceId(),
                event.getOrderId(),
                event.getSubscriptionId(),
                event.getCurrency(),
                event.getTitle(),
                event.getDescription(),
                event.getQuantity(),
                scale(event.getUnitAmount()),
                scale(event.getTaxAmount()),
                scale(event.getDiscountAmount()),
                event.getIssuedDate(),
                event.getDueDate(),
                event.getServicePeriodStart(),
                event.getServicePeriodEnd(),
                event.getExternalReference(),
                event.getNotes(),
                event.getAppliedInvoiceId(),
                event.getAppliedInvoiceNumber(),
                event.getProcessingStatus(),
                event.getCreatedAt(),
                event.getUpdatedAt()
        );
    }
}
