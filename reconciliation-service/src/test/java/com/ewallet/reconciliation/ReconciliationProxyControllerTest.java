package com.ewallet.reconciliation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

@WebMvcTest(ReconciliationProxyController.class)
class ReconciliationProxyControllerTest {
    @Autowired
    MockMvc mvc;

    @MockBean
    AccountServiceClient accountServiceClient;

    @Test
    void forwardsReconciliationHeadersAndResponse() throws Exception {
        when(accountServiceClient.runReconciliation(any(HttpHeaders.class)))
            .thenReturn(ResponseEntity.ok("{\"zeroDrift\":true}"));

        mvc.perform(post("/api/reconciliation/run")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .header("X-Trace-Id", "trace-1"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"zeroDrift\":true}"));

        ArgumentCaptor<HttpHeaders> headers = ArgumentCaptor.forClass(HttpHeaders.class);
        verify(accountServiceClient).runReconciliation(headers.capture());
        org.assertj.core.api.Assertions.assertThat(headers.getValue().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer token");
        org.assertj.core.api.Assertions.assertThat(headers.getValue().getFirst("X-Trace-Id")).isEqualTo("trace-1");
    }
}
