package io.github.marianciuc.streamingservice.billing.controller;

import io.github.marianciuc.streamingservice.billing.dto.request.CreateBillingInvoiceRequest;
import io.github.marianciuc.streamingservice.billing.dto.request.CreateBillingLineItemRequest;
import io.github.marianciuc.streamingservice.billing.dto.request.UpdateBillingInvoiceStatusRequest;
import io.github.marianciuc.streamingservice.billing.dto.response.BillingAccountSummaryResponse;
import io.github.marianciuc.streamingservice.billing.dto.response.BillingInvoiceResponse;
import io.github.marianciuc.streamingservice.billing.enums.InvoiceStatus;
import io.github.marianciuc.streamingservice.billing.service.BillingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;

    @PostMapping("/invoices")
    public ResponseEntity<BillingInvoiceResponse> createInvoice(@RequestBody @Valid CreateBillingInvoiceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(billingService.createInvoice(request));
    }

    @GetMapping("/invoices/{invoiceId}")
    public ResponseEntity<BillingInvoiceResponse> getInvoice(@PathVariable UUID invoiceId) {
        return ResponseEntity.ok(billingService.getInvoice(invoiceId));
    }

    @GetMapping("/invoices")
    public ResponseEntity<List<BillingInvoiceResponse>> listInvoices(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) InvoiceStatus status,
            @RequestParam(defaultValue = "false") boolean overdueOnly
    ) {
        return ResponseEntity.ok(billingService.listInvoices(userId, status, overdueOnly));
    }

    @PostMapping("/invoices/{invoiceId}/line-items")
    public ResponseEntity<BillingInvoiceResponse> addLineItem(
            @PathVariable UUID invoiceId,
            @RequestBody @Valid CreateBillingLineItemRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(billingService.addLineItem(invoiceId, request));
    }

    @PutMapping("/invoices/{invoiceId}/status")
    public ResponseEntity<BillingInvoiceResponse> updateInvoiceStatus(
            @PathVariable UUID invoiceId,
            @RequestBody @Valid UpdateBillingInvoiceStatusRequest request
    ) {
        return ResponseEntity.ok(billingService.updateInvoiceStatus(invoiceId, request));
    }

    @GetMapping("/accounts/{userId}/summary")
    public ResponseEntity<BillingAccountSummaryResponse> getAccountSummary(@PathVariable UUID userId) {
        return ResponseEntity.ok(billingService.getAccountSummary(userId));
    }
}
