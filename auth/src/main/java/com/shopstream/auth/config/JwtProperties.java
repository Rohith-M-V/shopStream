package com.shopstream.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String privateKeyLocation,
        String issuer,
        long expirationMinutes) {
}
