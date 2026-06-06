package com.ewallet.audit;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class AuditProxyController {
    private final AccountServiceClient accountServiceClient;

    public AuditProxyController(AccountServiceClient accountServiceClient) {
        this.accountServiceClient = accountServiceClient;
    }

    @GetMapping
    ResponseEntity<String> audit(@RequestHeader HttpHeaders headers) {
        return accountServiceClient.getAudit(headers);
    }
}
