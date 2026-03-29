package io.github.marianciuc.streamingservice.billing.enums;

public enum BillingBusinessEventType {
    ORDER_BOOKED,
    SUBSCRIPTION_RENEWED,
    PAYMENT_CAPTURED,
    PAYMENT_FAILED,
    RETRY_SCHEDULED,
    RECONCILIATION_RECORDED
}
