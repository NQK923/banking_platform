package com.ewallet.transaction;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transactions")
public class TransactionProxyController {
    private final AccountServiceClient accountServiceClient;

    public TransactionProxyController(AccountServiceClient accountServiceClient) {
        this.accountServiceClient = accountServiceClient;
    }

    @PostMapping("/transfer")
    ResponseEntity<String> transfer(@RequestBody String body, @RequestHeader HttpHeaders headers) {
        return accountServiceClient.exchange(HttpMethod.POST, "/api/transactions/transfer", body, headers);
    }

    @GetMapping("/{id}")
    ResponseEntity<String> get(@PathVariable String id, @RequestHeader HttpHeaders headers) {
        return accountServiceClient.exchange(HttpMethod.GET, "/api/transactions/" + id, null, headers);
    }

    @GetMapping
    ResponseEntity<String> list(@RequestHeader HttpHeaders headers) {
        return accountServiceClient.exchange(HttpMethod.GET, "/api/transactions", null, headers);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<String> cancel(@PathVariable String id, @RequestHeader HttpHeaders headers) {
        return accountServiceClient.exchange(HttpMethod.DELETE, "/api/transactions/" + id, null, headers);
    }
}
