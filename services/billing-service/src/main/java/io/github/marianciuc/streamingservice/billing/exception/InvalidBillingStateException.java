package io.github.marianciuc.streamingservice.billing.exception;

public class InvalidBillingStateException extends RuntimeException {

    public InvalidBillingStateException(String message) {
        super(message);
    }
}
