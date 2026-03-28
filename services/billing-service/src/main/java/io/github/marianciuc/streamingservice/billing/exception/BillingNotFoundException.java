package io.github.marianciuc.streamingservice.billing.exception;

public class BillingNotFoundException extends RuntimeException {

    public BillingNotFoundException(String message) {
        super(message);
    }
}
