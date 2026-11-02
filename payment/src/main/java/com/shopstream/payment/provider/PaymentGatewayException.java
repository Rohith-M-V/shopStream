package com.shopstream.payment.provider;

/**
 * Thrown when a {@link PaymentProvider} cannot complete a charge for
 * TECHNICAL reasons -- a network timeout, the gateway being unreachable, an
 * unexpected response shape. This is deliberately NOT a {@link PaymentResult}
 * decline: a real card decline is a normal, expected business outcome the
 * gateway hands back; being unable to reach the gateway at all is not.
 *
 * <p>Left uncaught by PaymentService on purpose, so it propagates out of the
 * Kafka listener and triggers {@code @RetryableTopic}'s retry-then-DLT
 * behavior -- the same category of failure as a database outage. Retrying a
 * declined card can't succeed; retrying a dropped connection might.
 */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }

    public PaymentGatewayException(String message) {
        super(message);
    }
}
