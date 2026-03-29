package io.github.marianciuc.streamingservice.billing.service;

import io.github.marianciuc.streamingservice.billing.dto.request.CreateBillingInvoiceRequest;
import io.github.marianciuc.streamingservice.billing.dto.request.CreateBillingLineItemRequest;
import io.github.marianciuc.streamingservice.billing.dto.request.CreateBillingBusinessEventRequest;
import io.github.marianciuc.streamingservice.billing.dto.request.UpdateBillingInvoiceStatusRequest;
import io.github.marianciuc.streamingservice.billing.dto.response.BillingAccountSummaryResponse;
import io.github.marianciuc.streamingservice.billing.dto.response.BillingBusinessEventResponse;
import io.github.marianciuc.streamingservice.billing.dto.response.BillingInvoiceResponse;
import io.github.marianciuc.streamingservice.billing.enums.BillingBusinessEventType;
import io.github.marianciuc.streamingservice.billing.enums.InvoiceStatus;

import java.util.List;
import java.util.UUID;

public interface BillingService {

    BillingInvoiceResponse createInvoice(CreateBillingInvoiceRequest request);

    BillingInvoiceResponse getInvoice(UUID invoiceId);

    List<BillingInvoiceResponse> listInvoices(UUID userId, InvoiceStatus status, boolean overdueOnly);

    BillingInvoiceResponse addLineItem(UUID invoiceId, CreateBillingLineItemRequest request);

    BillingInvoiceResponse updateInvoiceStatus(UUID invoiceId, UpdateBillingInvoiceStatusRequest request);

    BillingAccountSummaryResponse getAccountSummary(UUID userId);

    BillingBusinessEventResponse recordBusinessEvent(CreateBillingBusinessEventRequest request);

    List<BillingBusinessEventResponse> listBusinessEvents(UUID userId, BillingBusinessEventType eventType);
}
