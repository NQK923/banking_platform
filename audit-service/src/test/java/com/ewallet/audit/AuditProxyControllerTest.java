package com.ewallet.audit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuditProxyController.class)
class AuditProxyControllerTest {
    @Autowired
    MockMvc mvc;

    @MockBean
    AccountServiceClient accountServiceClient;

    @Test
    void forwardsAuditHeadersAndResponse() throws Exception {
        when(accountServiceClient.getAudit(any(HttpHeaders.class)))
            .thenReturn(ResponseEntity.ok("[{\"eventType\":\"AccountSuspended\"}]"));

        mvc.perform(get("/api/audit")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .header("X-Trace-Id", "trace-1")
                .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
            .andExpect(status().isOk())
            .andExpect(content().json("[{\"eventType\":\"AccountSuspended\"}]"));

        ArgumentCaptor<HttpHeaders> headers = ArgumentCaptor.forClass(HttpHeaders.class);
        verify(accountServiceClient).getAudit(headers.capture());
        org.assertj.core.api.Assertions.assertThat(headers.getValue().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer token");
        org.assertj.core.api.Assertions.assertThat(headers.getValue().getFirst("X-Trace-Id")).isEqualTo("trace-1");
        org.assertj.core.api.Assertions.assertThat(headers.getValue().getFirst("traceparent"))
            .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    }
}
