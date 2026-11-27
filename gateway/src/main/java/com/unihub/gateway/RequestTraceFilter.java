package com.unihub.gateway;

import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

@Component
class RequestTraceFilter implements GlobalFilter, Ordered {
    private static final String HEADER = "X-Request-ID";
    private static final Pattern VALID_ID = Pattern.compile("[0-9a-f]{32}");
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(RequestTraceFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest();
        String supplied = request.getHeaders().getFirst(HEADER);
        String id = supplied != null && VALID_ID.matcher(supplied).matches() ? supplied
                : UUID.randomUUID().toString().replace("-", "");
        var response = exchange.getResponse();
        response.beforeCommit(() -> {
            response.getHeaders().set(HEADER, id);
            LOG.info("API request_id={} method={} status={}", id, request.getMethod(), response.getStatusCode());
            return Mono.empty();
        });
        return chain.filter(exchange.mutate().request(request.mutate().headers(headers -> headers.set(HEADER, id)).build()).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
