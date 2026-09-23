package com.shopstream.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * The gateway is the first line of JWT validation: it decodes and verifies
 * every token using only the RSA public key (spring.security.oauth2.resourceserver.jwt.public-key-location
 * in application.yml) -- it never sees the private key that signs tokens.
 *
 * The gateway only checks "is this a valid token at all" (authentication),
 * not "is this role allowed to do X" (authorization) -- that fine-grained
 * check (e.g. only ADMIN can create a product) lives in each backend service
 * instead, since only that service really knows its own business rules. This
 * also means each service independently re-validates the token (see
 * inventory's own SecurityConfig) -- defense in depth, not duplicated trust.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/api/auth/register", "/api/auth/login").permitAll()
                        .pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Catalog browsing is public; inventory enforces ADMIN for writes itself.
                        .pathMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
