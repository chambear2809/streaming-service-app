package io.github.marianciuc.streamingservice.billing.entity;

import io.github.marianciuc.streamingservice.billing.enums.BillingCurrency;
import io.github.marianciuc.streamingservice.billing.enums.BillingCycle;
import io.github.marianciuc.streamingservice.billing.enums.InvoiceStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "billing_invoices")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "invoice_number", nullable = false, unique = true, length = 32)
    private String invoiceNumber;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "subscription_id")
    private UUID subscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private InvoiceStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", nullable = false, length = 32)
    private BillingCycle billingCycle;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 8)
    private BillingCurrency currency;

    @Column(name = "subtotal_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotalAmount;

    @Column(name = "tax_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal taxAmount;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "balance_due", nullable = false, precision = 12, scale = 2)
    private BigDecimal balanceDue;

    @Column(name = "issued_date", nullable = false)
    private LocalDate issuedDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "service_period_start")
    private LocalDate servicePeriodStart;

    @Column(name = "service_period_end")
    private LocalDate servicePeriodEnd;

    @Column(name = "external_payment_reference", length = 128)
    private String externalPaymentReference;

    @Column(name = "notes", length = 1024)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder.Default
    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    private List<BillingInvoiceLineItem> lineItems = new ArrayList<>();

    public void addLineItem(BillingInvoiceLineItem lineItem) {
        lineItems.add(lineItem);
        lineItem.setInvoice(this);
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
