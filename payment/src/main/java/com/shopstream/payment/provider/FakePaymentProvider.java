package com.shopstream.payment.provider;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A deterministic stand-in for a real gateway, needed because this project
 * has no real PayPal/Stripe sandbox credentials wired up, and because
 * automated tests need a predictable, offline-runnable outcome rather than a
 * real network call.
 *
 * <p>The order flow doesn't carry a real price yet (no product-pricing
 * lookup is wired into order creation), so quantity stands in as the "amount"
 * for this simulation -- a known simplification, flagged here rather than
 * hidden. The convention mirrors how real sandboxes work: Stripe's test mode
 * uses specific card numbers to simulate declines; this uses a specific
 * quantity (99) the same way.
 */
@Component
public class FakePaymentProvider implements PaymentProvider {

    private static final int DECLINE_QUANTITY_SENTINEL = 99;

    @Override
    public PaymentResult charge(String orderId, UUID customerId, UUID productId, int quantity) {
        if (quantity == DECLINE_QUANTITY_SENTINEL) {
            return PaymentResult.declined("Card declined (simulated)");
        }
        return PaymentResult.successful();
    }
}
