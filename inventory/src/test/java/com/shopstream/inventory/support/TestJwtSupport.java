package com.shopstream.inventory.support;

import com.shopstream.common.security.PemKeyLoader;
import io.jsonwebtoken.Jwts;
import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * Mints RS256 JWTs shaped exactly like the real auth service's, using the
 * SAME dev key pair -- but the private key here lives only under
 * src/test/resources/keys, never in src/main/resources. This lets inventory's
 * tests exercise real signature verification without needing the auth
 * service running.
 */
public final class TestJwtSupport {

    private static final String ISSUER = "shopstream-auth";

    private TestJwtSupport() {
    }

    public static String token(String role) {
        try (InputStream in = TestJwtSupport.class.getClassLoader().getResourceAsStream("keys/private_key.pem")) {
            if (in == null) {
                throw new IllegalStateException("Test key not found on classpath: keys/private_key.pem");
            }
            RSAPrivateKey privateKey = PemKeyLoader.loadPrivateKey(in);
            Instant now = Instant.now();
            return Jwts.builder()
                    .subject(UUID.randomUUID().toString())
                    .claim("email", "test-" + role.toLowerCase() + "@example.com")
                    .claim("role", role)
                    .issuer(ISSUER)
                    .issuedAt(Date.from(now))
                    .expiration(Date.from(now.plus(1, ChronoUnit.HOURS)))
                    .signWith(privateKey, Jwts.SIG.RS256)
                    .compact();
        } catch (IOException e) {
            throw new IllegalStateException("Could not mint test token", e);
        }
    }
}
