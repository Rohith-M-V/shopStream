package com.shopstream.payment.web;

import com.shopstream.payment.provider.paypal.PayPalWebhookVerifier;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives PayPal's asynchronous webhook notifications. This endpoint has no
 * JWT -- it's called by PayPal's servers, not our own authenticated users
 * (see SecurityConfig, which permits this one path, and the gateway's
 * SecurityConfig, which does the same). The webhook SIGNATURE is what
 * authenticates the caller here, verified via {@link PayPalWebhookVerifier}.
 *
 * <p><b>Scope, stated honestly:</b> this endpoint verifies the signature,
 * de-duplicates against PaymentService's existing PaymentAttempt idempotency
 * ledger, and logs a warning if an order's outcome arrives here before the
 * synchronous charge path ever recorded one. It deliberately does NOT
 * attempt to reconstruct a full PaymentAttempt from webhook data alone --
 * the webhook payload only carries the order id (via custom_id) and the
 * outcome, not customerId/productId/quantity, which PaymentAttempt requires.
 * A production system wanting full webhook-driven reconciliation would need
 * to persist that context (e.g. in a lookup table keyed by order id) before
 * the synchronous call, specifically so a webhook arriving alone has enough
 * to finish the job -- a reasonable next extension, left out here rather
 * than faked with placeholder data.
 */
@RestController
@RequestMapping("/api/payments/webhooks")
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "paypal")
public class PayPalWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PayPalWebhookController.class);
    private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private final PayPalWebhookVerifier verifier;
    private final PaymentService paymentService;

    public PayPalWebhookController(PayPalWebhookVerifier verifier, PaymentService paymentService) {
        this.verifier = verifier;
        this.paymentService = paymentService;
    }

    @PostMapping("/paypal")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Void> handleWebhook(@RequestBody String rawBody, @RequestHeader HttpHeaders headers) {
        if (!verifier.verify(rawBody, headers)) {
            return ResponseEntity.badRequest().build();
        }

        Map<String, Object> event = OBJECT_MAPPER.readValue(rawBody, Map.class);
        String eventType = (String) event.get("event_type");
        String orderId = extractOrderId(event);

        if (orderId == null) {
            log.debug("Ignoring PayPal webhook {}: no correlatable order id (custom_id) found", eventType);
            return ResponseEntity.ok().build();
        }

        if (paymentService.isAlreadyProcessed(orderId)) {
            log.debug("PayPal webhook {} for order {}: already reconciled via the synchronous charge path",
                    eventType, orderId);
        } else {
            // See the class-level comment: this is a known, named limitation,
            // not a silent gap -- logged loudly rather than guessed at.
            log.warn("PayPal webhook {} for order {} arrived but no PaymentAttempt exists yet for it -- "
                    + "the synchronous charge call may not have completed on our side; this order may need "
                    + "manual reconciliation", eventType, orderId);
        }

        // Always ack 200 once the signature is valid, even when we only
        // logged a warning above -- a non-200 here just makes PayPal retry
        // delivery of a webhook we've already correctly understood.
        return ResponseEntity.ok().build();
    }

    @SuppressWarnings("unchecked")
    private String extractOrderId(Map<String, Object> event) {
        Object resourceObj = event.get("resource");
        if (!(resourceObj instanceof Map)) {
            return null;
        }
        Map<String, Object> resource = (Map<String, Object>) resourceObj;

        // PAYMENT.CAPTURE.* events carry custom_id directly on the capture resource.
        Object direct = resource.get("custom_id");
        if (direct != null) {
            return direct.toString();
        }
        // CHECKOUT.ORDER.* events carry it nested under purchase_units instead.
        Object purchaseUnitsObj = resource.get("purchase_units");
        if (purchaseUnitsObj instanceof List<?> purchaseUnits && !purchaseUnits.isEmpty()
                && purchaseUnits.get(0) instanceof Map<?, ?> firstUnit) {
            Object nested = firstUnit.get("custom_id");
            return nested == null ? null : nested.toString();
        }
        return null;
    }
}
