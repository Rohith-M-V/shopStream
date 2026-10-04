package com.shopstream.payment.provider;

import java.util.UUID;

/**
 * The abstraction that makes the rest of this service ignorant of which
 * actual payment gateway is behind it. {@link FakePaymentProvider} is the
 * only implementation today -- a real one (Stripe, PayPal) would implement
 * this exact interface, and PaymentService would not need to change at all
 * to use it. This is also what makes PaymentService's logic testable without
 * real network calls or sandbox credentials.
 */
public interface PaymentProvider {

    PaymentResult charge(String orderId, UUID customerId, UUID productId, int quantity);
}
