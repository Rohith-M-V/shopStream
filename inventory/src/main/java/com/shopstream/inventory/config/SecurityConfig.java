package com.shopstream.inventory.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Inventory validates JWTs independently of the gateway (defense in depth):
 * even a request that somehow reached this service directly, bypassing the
 * gateway, still can't get past this filter chain without a valid signature.
 *
 * <p>Unlike the gateway (which only checks "is this token valid at all"),
 * inventory also enforces a specific ROLE for mutating endpoints. Spring
 * Security's default JWT authority mapping looks for an OAuth2 "scope"/"scp"
 * claim, which our tokens don't have -- ours carry a custom "role" claim
 * instead (see auth's JwtService), so we plug in a converter that reads
 * THAT claim and turns it into a Spring Security authority ("ROLE_ADMIN"),
 * which is what hasRole("ADMIN") below checks against.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Catalog browsing is public -- no login needed to see products.
                        .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Creating/editing catalog entries is an admin action.
                        .requestMatchers(HttpMethod.POST, "/api/products").hasRole("ADMIN")
                        // Reserve/release: any authenticated caller for now. Once the order
                        // service exists, this is where service-to-service auth would tighten
                        // this further (see the architecture notes on short-lived service tokens).
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(
                        jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    private Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            if (role == null || role.isBlank()) {
                return List.of();
            }
            return List.of(new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter;
    }
}
