package com.shopstream.auth.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.core.io.Resource;

/**
 * Reads plain PEM files (PKCS8 private key / X.509 public key) into java.security
 * key objects. No external crypto library needed for this -- just the JDK.
 *
 * <p>Generate a matching pair with:
 * <pre>
 *   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private_key.pem
 *   openssl rsa -pubout -in private_key.pem -out public_key.pem
 * </pre>
 */
public final class PemKeyLoader {

    private PemKeyLoader() {
    }

    public static RSAPrivateKey loadPrivateKey(Resource resource) {
        byte[] der = Base64.getDecoder().decode(stripPemHeaders(resource));
        try {
            var spec = new PKCS8EncodedKeySpec(der);
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Could not parse RSA private key from " + resource, e);
        }
    }

    public static RSAPublicKey loadPublicKey(Resource resource) {
        byte[] der = Base64.getDecoder().decode(stripPemHeaders(resource));
        try {
            var spec = new X509EncodedKeySpec(der);
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Could not parse RSA public key from " + resource, e);
        }
    }

    private static String stripPemHeaders(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return content
                    .replaceAll("-----BEGIN (.*)-----", "")
                    .replaceAll("-----END (.*)-----", "")
                    .replaceAll("\\s", "");
        } catch (IOException e) {
            throw new IllegalStateException("Could not read key resource " + resource, e);
        }
    }
}
