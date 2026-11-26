package com.unihub.backend.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    private static final String HEADER = "X-Request-ID";
    private static final Pattern VALID_ID = Pattern.compile("[0-9a-f]{32}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        if (id == null || !VALID_ID.matcher(id).matches()) {
            id = UUID.randomUUID().toString().replace("-", "");
        }
        response.setHeader(HEADER, id);
        try (var ignored = MDC.putCloseable("requestId", id)) {
            chain.doFilter(request, response);
        }
    }
}
