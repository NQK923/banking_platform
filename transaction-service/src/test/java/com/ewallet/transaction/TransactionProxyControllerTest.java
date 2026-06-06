package com.ewallet.transaction;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TransactionProxyController.class)
class TransactionProxyControllerTest {
    @Autowired
    MockMvc mvc;

    @MockBean
    AccountServiceClient accountServiceClient;

    @Test
    void forwardsTransferBodyAndIdempotencyHeaders() throws Exception {
        String body = "{\"amount\":\"100\",\"recipientEmail\":\"receiver@example.test\"}";
        when(accountServiceClient.exchange(eq(HttpMethod.POST), eq("/api/transactions/transfer"), eq(body), org.mockito.ArgumentMatchers.any(HttpHeaders.class)))
            .thenReturn(ResponseEntity.accepted().body("{\"status\":\"PENDING\"}"));

        mvc.perform(post("/api/transactions/transfer")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .header("Idempotency-Key", "idem-1")
                .header("X-Trace-Id", "trace-1")
                .content(body))
            .andExpect(status().isAccepted())
            .andExpect(content().json("{\"status\":\"PENDING\"}"));

        ArgumentCaptor<HttpHeaders> headers = ArgumentCaptor.forClass(HttpHeaders.class);
        verify(accountServiceClient).exchange(eq(HttpMethod.POST), eq("/api/transactions/transfer"), eq(body), headers.capture());
        org.assertj.core.api.Assertions.assertThat(headers.getValue().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer token");
        org.assertj.core.api.Assertions.assertThat(headers.getValue().getFirst("Idempotency-Key")).isEqualTo("idem-1");
        org.assertj.core.api.Assertions.assertThat(headers.getValue().getFirst("X-Trace-Id")).isEqualTo("trace-1");
    }
}
