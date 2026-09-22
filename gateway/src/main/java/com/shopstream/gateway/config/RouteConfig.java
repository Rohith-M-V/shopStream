package com.shopstream.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Routes are defined here in Java, not in application.yml. Spring Cloud
 * Gateway's YAML property prefix has changed across recent releases (it moved
 * under "spring.cloud.gateway.server.webflux.*" when the server/proxy split
 * happened); the RouteLocatorBuilder API is stable regardless of that, so we
 * use it directly to avoid pinning to one release's property names.
 */
@Configuration
public class RouteConfig {

    @Value("${services.auth.uri:http://localhost:8081}")
    private String authServiceUri;

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("auth-service", r -> r
                        .path("/api/auth/**")
                        .uri(authServiceUri))
                .build();
    }
}
