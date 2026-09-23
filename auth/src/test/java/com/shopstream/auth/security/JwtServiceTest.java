package com.shopstream.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.shopstream.auth.config.JwtProperties;
import com.shopstream.auth.user.User;
import com.shopstream.common.security.PemKeyLoader;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class JwtServiceTest {

    private final DefaultResourceLoader resourceLoader = new DefaultResourceLoader();

    private JwtService newJwtService(long expirationMinutes) {
        JwtProperties properties = new JwtProperties(
                "classpath:keys/private_key.pem", "shopstream-auth", expirationMinutes);
        return new JwtService(properties, resourceLoader);
    }

    @Test
    void generatedTokenCanBeVerifiedWithTheMatchingPublicKey() throws java.io.IOException {
        JwtService jwtService = newJwtService(60);
        User user = User.newCustomer("jane@example.com", "irrelevant-hash");

        String token = jwtService.generateAccessToken(user);

        var publicKey = PemKeyLoader.loadPublicKey(
                resourceLoader.getResource("classpath:keys/public_key.pem").getInputStream());
        Claims claims = Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
        assertThat(claims.getIssuer()).isEqualTo("shopstream-auth");
        assertThat(claims.get("email", String.class)).isEqualTo("jane@example.com");
        assertThat(claims.get("role", String.class)).isEqualTo("CUSTOMER");
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }

    @Test
    void expirationSecondsMatchesConfiguredMinutes() {
        JwtService jwtService = newJwtService(15);

        assertThat(jwtService.expirationSeconds()).isEqualTo(15 * 60L);
    }
}
