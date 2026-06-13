package com.ewallet.account.web;

import com.ewallet.account.model.WalletTransaction;
import com.ewallet.account.security.AuthenticatedUser;
import com.ewallet.account.service.TransferUseCases;
import com.ewallet.account.service.TransferUseCases.TransferRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
public class TransactionController {
    private final TransferUseCases transferUseCases;

    public TransactionController(TransferUseCases transferUseCases) {
        this.transferUseCases = transferUseCases;
    }

    @PostMapping("/transfer")
    Object transfer(
        @AuthenticationPrincipal AuthenticatedUser user,
        @RequestBody TransferRequest request,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return transferUseCases.transfer(user, request, idempotencyKey);
    }

    @GetMapping("/{id}")
    WalletTransaction get(@PathVariable UUID id) {
        return transferUseCases.get(id);
    }

    @GetMapping
    List<WalletTransaction> list() {
        return transferUseCases.list();
    }

    @DeleteMapping("/{id}")
    WalletTransaction cancel(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        return transferUseCases.cancel(id, user);
    }
}
