package com.shopstream.payment.provider.paypal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All values default to blank/zero so the application still starts cleanly
 * with the fake provider active and none of these set. They only need real
 * values when {@code app.payment.provider=paypal} is actually turned on --
 * see the README section on PayPal sandbox setup for where each one comes
 * from in the PayPal Developer Dashboard.
 */
@ConfigurationProperties(prefix = "paypal")
public record PayPalProperties(
        String baseUrl,
        String clientId,
        String clientSecret,
        String webhookId,
        TestCard testCard) {

    /**
     * A sandbox-only test card number (PayPal's Orders API accepts raw card
     * details directly for guest/card checkout, which is what lets this
     * provider charge synchronously with no buyer redirect -- see
     * PayPalPaymentProvider's class comment for why that matters here).
     * Generate one from PayPal Developer Dashboard > Sandbox > Testing Tools
     * > Credit Card Generator. NEVER a real card number, obviously.
     */
    public record TestCard(String number, String expiry, String cvv) {
    }
}
