package com.shopstream.payment.provider.paypal;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies a webhook actually came from PayPal, using PayPal's own "Verify
 * Webhook Signature" API rather than implementing raw cryptographic
 * cert-chain verification by hand -- PayPal documents this as the
 * recommended approach for most integrations, and it's far less brittle than
 * hand-rolled signature verification code.
 */
@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "paypal")
public class PayPalWebhookVerifier {

    private static final Logger log = LoggerFactory.getLogger(PayPalWebhookVerifier.class);

    private final RestClient restClient;
    private final PayPalAuthClient authClient;
    private final PayPalProperties properties;

    public PayPalWebhookVerifier(RestClient payPalRestClient, PayPalAuthClient authClient,
            PayPalProperties properties) {
        this.restClient = payPalRestClient;
        this.authClient = authClient;
        this.properties = properties;
    }

    public boolean verify(String rawBody, HttpHeaders headers) {
        String transmissionId = headers.getFirst("PAYPAL-TRANSMISSION-ID");
        String transmissionTime = headers.getFirst("PAYPAL-TRANSMISSION-TIME");
        String certUrl = headers.getFirst("PAYPAL-CERT-URL");
        String authAlgo = headers.getFirst("PAYPAL-AUTH-ALGO");
        String transmissionSig = headers.getFirst("PAYPAL-TRANSMISSION-SIG");

        if (transmissionId == null || transmissionTime == null || certUrl == null
                || authAlgo == null || transmissionSig == null) {
            log.warn("Rejecting webhook: missing one or more required PayPal signature headers");
            return false;
        }

        Object webhookEvent = JsonMapper.builder().build().readValue(rawBody, Object.class);

        Map<String, Object> verifyRequest = Map.of(
                "transmission_id", transmissionId,
                "transmission_time", transmissionTime,
                "cert_url", certUrl,
                "auth_algo", authAlgo,
                "transmission_sig", transmissionSig,
                "webhook_id", properties.webhookId(),
                "webhook_event", webhookEvent);

        try {
            Map<String, Object> response = restClient.post()
                    .uri("/v1/notifications/verify-webhook-signature")
                    .headers(h -> h.setBearerAuth(authClient.getAccessToken()))
                    .body(verifyRequest)
                    .retrieve()
                    .body(Map.class);

            boolean verified = response != null && "SUCCESS".equals(response.get("verification_status"));
            if (!verified) {
                log.warn("PayPal webhook signature verification failed: {}", response);
            }
            return verified;
        } catch (Exception e) {
            log.error("Error calling PayPal's webhook verification API", e);
            return false;
        }
    }
}
