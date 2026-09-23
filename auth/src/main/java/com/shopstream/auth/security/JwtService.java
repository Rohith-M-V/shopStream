package com.shopstream.auth.security;

import com.shopstream.auth.config.JwtProperties;
import com.shopstream.auth.user.User;
import com.shopstream.common.security.PemKeyLoader;
import io.jsonwebtoken.Jwts;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * Issues RS256-signed JWTs. Only this service holds the private key; every
 * other service (starting with the gateway) verifies tokens using only the
 * public key, via Spring Security's oauth2-resource-server support.
 */
@Service
public class JwtService {

    private final RSAPrivateKey privateKey;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        try {
            var resource = resourceLoader.getResource(properties.privateKeyLocation());
            this.privateKey = PemKeyLoader.loadPrivateKey(resource.getInputStream());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read " + properties.privateKeyLocation(), e);
        }
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.expirationMinutes(), ChronoUnit.MINUTES);

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuer(properties.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    public long expirationSeconds() {
        return properties.expirationMinutes() * 60;
    }
}
