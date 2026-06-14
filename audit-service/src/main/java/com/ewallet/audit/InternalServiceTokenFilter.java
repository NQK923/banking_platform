package com.ewallet.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class InternalServiceTokenFilter extends OncePerRequestFilter {
    private final String token;

    InternalServiceTokenFilter(@Value("${banking.internal-service-token:}") String token) {
        this.token = token;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/actuator/health")) {
            filterChain.doFilter(request, response);
            return;
        }
        if (token.isBlank() || !token.equals(request.getHeader("X-Service-Token"))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid internal service token");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
