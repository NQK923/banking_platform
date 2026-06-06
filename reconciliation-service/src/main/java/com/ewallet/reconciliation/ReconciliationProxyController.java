package com.ewallet.reconciliation;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reconciliation")
public class ReconciliationProxyController {
    private final AccountServiceClient accountServiceClient;

    public ReconciliationProxyController(AccountServiceClient accountServiceClient) {
        this.accountServiceClient = accountServiceClient;
    }

    @PostMapping("/run")
    ResponseEntity<String> run(@RequestHeader HttpHeaders headers) {
        return accountServiceClient.runReconciliation(headers);
    }
}
