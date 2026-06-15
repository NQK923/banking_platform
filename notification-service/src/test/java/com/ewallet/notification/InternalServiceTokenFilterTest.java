package com.ewallet.notification;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InternalServiceTokenFilterTest {
    @Test
    void throwsExceptionWhenTokenIsNotConfigured() {
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> new InternalServiceTokenFilter("")
        );
    }

    @Test
    void rejectsDlqRequestWithWrongToken() throws ServletException, IOException {
        InternalServiceTokenFilter filter = new InternalServiceTokenFilter("expected-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/dlq");
        request.addHeader("X-Service-Token", "wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void allowsDlqRequestWithMatchingToken() throws ServletException, IOException {
        InternalServiceTokenFilter filter = new InternalServiceTokenFilter("expected-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/dlq");
        request.addHeader("X-Service-Token", "expected-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void allowsHealthCheckWithoutToken() throws ServletException, IOException {
        InternalServiceTokenFilter filter = new InternalServiceTokenFilter("expected-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }
}
