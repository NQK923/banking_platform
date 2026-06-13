package com.ewallet.account.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ewallet.common.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class BearerTokenFilterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void expiredBearerTokenReturnsUnauthorizedJsonAndStopsChain() throws Exception {
        TokenService tokenService = mock(TokenService.class);
        when(tokenService.parseAccessToken(anyString()))
            .thenThrow(new DomainException("AUTH_EXPIRED", "Access token expired"));
        BearerTokenFilter filter = new BearerTokenFilter(tokenService, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/accounts/account-id/balance");
        request.addHeader("Authorization", "Bearer expired-token");
        request.addHeader("X-Trace-Id", "trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("AUTH_EXPIRED");
        assertThat(body.get("message").asText()).isEqualTo("Access token expired");
        assertThat(body.get("traceId").asText()).isEqualTo("trace-123");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(tokenService).parseAccessToken("expired-token");
        verifyNoInteractions(chain);
    }
}
