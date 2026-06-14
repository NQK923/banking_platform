package com.ewallet.account.web;

import com.ewallet.common.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/admin/dlq")
class AdminDlqProxyController {
    private final RestTemplate restTemplate;
    private final String notificationServiceUrl;
    private final String internalServiceToken;

    AdminDlqProxyController(
        RestTemplateBuilder restTemplateBuilder,
        @Value("${banking.notification-service-url}") String notificationServiceUrl,
        @Value("${banking.internal-service-token}") String internalServiceToken
    ) {
        this.restTemplate = restTemplateBuilder.build();
        this.notificationServiceUrl = notificationServiceUrl;
        this.internalServiceToken = internalServiceToken;
    }

    @GetMapping
    JsonNode inspect(@RequestParam(defaultValue = "50") int limit) {
        URI uri = UriComponentsBuilder
            .fromHttpUrl(notificationServiceUrl)
            .path("/api/admin/dlq")
            .queryParam("limit", limit)
            .build()
            .toUri();
        return exchange(uri, HttpMethod.GET, null);
    }

    @PostMapping("/replay")
    JsonNode replay(@RequestBody JsonNode request) {
        URI uri = UriComponentsBuilder
            .fromHttpUrl(notificationServiceUrl)
            .path("/api/admin/dlq/replay")
            .build()
            .toUri();
        return exchange(uri, HttpMethod.POST, request);
    }

    private JsonNode exchange(URI uri, HttpMethod method, JsonNode body) {
        if (internalServiceToken == null || internalServiceToken.isBlank()) {
            throw new DomainException("INTERNAL_SERVICE_TOKEN_REQUIRED", "Internal service token is not configured");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Service-Token", internalServiceToken);
        try {
            return restTemplate.exchange(uri, method, new HttpEntity<>(body, headers), JsonNode.class).getBody();
        } catch (RestClientException ex) {
            throw new DomainException("DLQ_PROXY_FAILED", "Unable to reach notification DLQ service");
        }
    }
}
