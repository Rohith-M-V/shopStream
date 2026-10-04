package com.shopstream.payment.provider;

/**
 * Notice a decline is modelled as a return VALUE here, not a thrown
 * exception -- unlike inventory's InsufficientStockException. Both are
 * legitimate ways to represent an expected business outcome; a declined
 * charge is a completely normal response from a real payment gateway (a
 * typed result the gateway's SDK hands back), not an exceptional condition,
 * so a return value is the more natural fit here.
 */
public record PaymentResult(boolean success, String reason) {

    public static PaymentResult successful() {
        return new PaymentResult(true, null);
    }

    public static PaymentResult declined(String reason) {
        return new PaymentResult(false, reason);
    }
}
