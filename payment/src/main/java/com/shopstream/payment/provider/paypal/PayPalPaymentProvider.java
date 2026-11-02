package com.shopstream.payment.provider.paypal;

import com.shopstream.payment.provider.PaymentGatewayException;
import com.shopstream.payment.provider.PaymentProvider;
import com.shopstream.payment.provider.PaymentResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * A real PayPal integration -- selected over {@link
 * com.shopstream.payment.provider.FakePaymentProvider} via
 * {@code app.payment.provider=paypal} (see application.yml and the README
 * for the sandbox credentials this needs).
 *
 * <p><b>Why card payment_source, not the usual PayPal wallet checkout:</b>
 * the standard PayPal checkout flow requires the buyer to be redirected to
 * paypal.com to approve the payment, which doesn't fit a backend-only
 * project with no UI. PayPal's Orders API also accepts raw card details
 * directly as {@code payment_source.card} (guest/direct card checkout),
 * which creates AND captures the order in one server-to-server call with no
 * redirect -- the only PayPal flow that fits this project's shape. In
 * sandbox, PayPal provides test card numbers for exactly this purpose.
 *
 * <p><b>Idempotency at the gateway itself:</b> the {@code PayPal-Request-Id}
 * header is set to the order id, so retrying this exact call (e.g. after our
 * own process crashed between sending the request and reading the response)
 * returns PayPal's ORIGINAL result instead of creating a second charge. This
 * is a different, additional layer from PaymentService's own
 * PaymentAttempt-based idempotency: that one protects against processing the
 * same Kafka message twice; this one protects against this provider's own
 * HTTP call being retried.
 *
 * <p><b>Correlating back to our order:</b> {@code custom_id} on the purchase
 * unit is set to the order id, so PayPal's webhook events (see
 * PayPalWebhookController) can report back which of OUR orders they concern.
 */
@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "paypal")
public class PayPalPaymentProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(PayPalPaymentProvider.class);

    private final RestClient restClient;
    private final PayPalAuthClient authClient;
    private final PayPalProperties properties;

    public PayPalPaymentProvider(RestClient payPalRestClient, PayPalAuthClient authClient,
            PayPalProperties properties) {
        this.restClient = payPalRestClient;
        this.authClient = authClient;
        this.properties = properties;
    }

    @Override
    public PaymentResult charge(String orderId, UUID customerId, UUID productId, int quantity) {
        // No real product-pricing lookup is wired into order creation yet
        // (same known simplification as FakePaymentProvider) -- a nominal
        // $1.00-per-unit amount stands in for a real price.
        String amount = "%d.00".formatted(quantity);

        Map<String, Object> requestBody = Map.of(
                "intent", "CAPTURE",
                "purchase_units", List.of(Map.of(
                        "custom_id", orderId,
                        "amount", Map.of(
                                "currency_code", "USD",
                                "value", amount))),
                "payment_source", Map.of(
                        "card", Map.of(
                                "number", properties.testCard().number(),
                                "expiry", properties.testCard().expiry(),
                                "security_code", properties.testCard().cvv())));

        try {
            Map<String, Object> response = restClient.post()
                    .uri("/v2/checkout/orders")
                    .headers(headers -> {
                        headers.setBearerAuth(authClient.getAccessToken());
                        // Retrying this exact call (same orderId) is then safe
                        // at the PayPal side too, not just at ours.
                        headers.set("PayPal-Request-Id", orderId);
                    })
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            return interpretResponse(orderId, response);
        } catch (HttpClientErrorException.UnprocessableEntity e) {
            // PayPal's standard shape for a declined card: 422 with error
            // details rather than a network-level failure -- a genuine
            // business outcome, not a technical one.
            String reason = extractDeclineReason(e);
            log.info("PayPal declined order {}: {}", orderId, reason);
            return PaymentResult.declined(reason);
        } catch (PaymentGatewayException e) {
            throw e;
        } catch (Exception e) {
            // Network failure, timeout, 5xx, malformed response -- all
            // technical, not business, failures. Left to propagate so
            // @RetryableTopic retries the whole message.
            throw new PaymentGatewayException("PayPal charge failed for order " + orderId, e);
        }
    }

    @SuppressWarnings("unchecked")
    private PaymentResult interpretResponse(String orderId, Map<String, Object> response) {
        if (response == null) {
            throw new PaymentGatewayException("PayPal returned an empty response for order " + orderId);
        }
        String status = (String) response.get("status");
        if ("COMPLETED".equals(status)) {
            return PaymentResult.successful();
        }
        // PAYER_ACTION_REQUIRED (e.g. a 3D Secure challenge) needs a browser
        // redirect this backend-only project has no way to complete -- a real
        // limitation of the card-payment_source approach, not a bug. Treated
        // as a gateway failure rather than silently mis-reporting it as a
        // decline.
        throw new PaymentGatewayException(
                "Unexpected PayPal order status '" + status + "' for order " + orderId
                        + " -- this provider cannot complete a flow requiring buyer redirect");
    }

    private String extractDeclineReason(HttpClientErrorException e) {
        try {
            Map<String, Object> body = e.getResponseBodyAs(Map.class);
            if (body != null && body.get("details") instanceof List<?> details && !details.isEmpty()
                    && details.get(0) instanceof Map<?, ?> firstDetail) {
                Object issue = firstDetail.get("issue");
                if (issue != null) {
                    return issue.toString();
                }
            }
        } catch (Exception ignored) {
            // Fall through to the generic reason below -- we already know
            // this was a decline (422), just without a specific issue code.
        }
        return "Card declined";
    }
}
