package com.shopstream.payment.provider.paypal;

import com.shopstream.payment.provider.PaymentGatewayException;
import java.time.Instant;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * PayPal's access tokens are valid for roughly 9 hours; re-authenticating on
 * every single API call would be both slow and needless load on PayPal's
 * auth endpoint. This caches the token in memory and only re-fetches once it
 * (or is about to) expire.
 */
@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "paypal")
public class PayPalAuthClient {

    private static final String TOKEN_PATH = "/v1/oauth2/token";
    // Refresh a little early so a request already in flight never gets an
    // about-to-expire token.
    private static final long EXPIRY_SAFETY_MARGIN_SECONDS = 60;

    private final RestClient restClient;
    private final PayPalProperties properties;

    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiresAt = Instant.EPOCH;

    public PayPalAuthClient(RestClient payPalRestClient, PayPalProperties properties) {
        this.restClient = payPalRestClient;
        this.properties = properties;
    }

    public String getAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiresAt)) {
            return cachedToken;
        }
        return fetchAndCacheToken();
    }

    private synchronized String fetchAndCacheToken() {
        // Double-checked: another thread may have already refreshed while
        // this one was waiting to enter the synchronized block.
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiresAt)) {
            return cachedToken;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");

        try {
            Map<String, Object> response = restClient.post()
                    .uri(TOKEN_PATH)
                    .headers(headers -> headers.setBasicAuth(properties.clientId(), properties.clientSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);

            if (response == null || response.get("access_token") == null) {
                throw new PaymentGatewayException("PayPal token response had no access_token");
            }

            cachedToken = (String) response.get("access_token");
            long expiresInSeconds = ((Number) response.getOrDefault("expires_in", 0)).longValue();
            cachedTokenExpiresAt = Instant.now().plusSeconds(
                    Math.max(0, expiresInSeconds - EXPIRY_SAFETY_MARGIN_SECONDS));
            return cachedToken;
        } catch (PaymentGatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new PaymentGatewayException("Failed to obtain a PayPal access token", e);
        }
    }
}
