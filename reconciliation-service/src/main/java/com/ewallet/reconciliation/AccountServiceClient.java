package com.ewallet.reconciliation;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

@Component
public class AccountServiceClient {
    private final RestTemplate restTemplate;
    private final String baseUrl;

    public AccountServiceClient(RestTemplateBuilder builder, @Value("${banking.account-service.base-url}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.restTemplate = builder.errorHandler(new PassThroughErrorHandler()).build();
    }

    public ResponseEntity<String> runReconciliation(HttpHeaders inboundHeaders) {
        HttpHeaders headers = new HttpHeaders();
        copy(inboundHeaders, headers, HttpHeaders.AUTHORIZATION);
        copy(inboundHeaders, headers, "X-Trace-Id");
        return restTemplate.exchange(baseUrl + "/api/reconciliation/run", HttpMethod.POST, new HttpEntity<>("{}", headers), String.class);
    }

    private void copy(HttpHeaders inbound, HttpHeaders outbound, String name) {
        String value = inbound.getFirst(name);
        if (value != null && !value.isBlank()) {
            outbound.set(name, value);
        }
    }

    private static final class PassThroughErrorHandler implements ResponseErrorHandler {
        @Override
        public boolean hasError(ClientHttpResponse response) throws IOException {
            return false;
        }

        @Override
        public void handleError(ClientHttpResponse response) throws IOException {
        }
    }
}
