package com.unihub.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.server.WebFilter;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

@Configuration(proxyBeanMethods = false)
class GatewayCorsConfiguration {

    @Bean
    WebFilter apiPreflightFilter() {
        // WebFlux consumes preflight requests before they reach a gateway route.
        // Match the backend's SecurityConfig for OPTIONS while it handles ordinary API responses.
        var apiPath = PathPatternParser.defaultInstance.parse("/api/**");
        var cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(List.of("*"));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Idempotency-Key"));
        cors.setAllowCredentials(true);
        var preflight = new CorsWebFilter(exchange -> cors);

        return (exchange, chain) -> {
            var request = exchange.getRequest();
            if (CorsUtils.isPreFlightRequest(request) && apiPath.matches(request.getPath().pathWithinApplication())) {
                return preflight.filter(exchange, chain);
            }
            return chain.filter(exchange);
        };
    }
}
