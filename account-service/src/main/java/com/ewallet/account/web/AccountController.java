package com.ewallet.account.web;

import com.ewallet.account.model.AccountRecord;
import com.ewallet.account.model.LedgerEntryRecord;
import com.ewallet.account.model.WalletTransaction;
import com.ewallet.account.service.AccountUseCases;
import com.ewallet.account.service.AccountUseCases.BalanceResponse;
import com.ewallet.account.service.AccountUseCases.MoneyRequest;
import com.ewallet.account.service.AccountUseCases.MovementResponse;
import com.ewallet.account.service.AuthService;
import com.ewallet.account.service.AuthService.AuthResponse;
import com.ewallet.account.service.AuthService.RegisterRequest;
import com.ewallet.account.security.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    private final AccountUseCases accountUseCases;
    private final AuthService authService;

    public AccountController(AccountUseCases accountUseCases, AuthService authService) {
        this.accountUseCases = accountUseCases;
        this.authService = authService;
    }

    @PostMapping
    AuthResponse create(@RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @GetMapping("/{id}")
    AccountRecord get(@PathVariable UUID id) {
        return accountUseCases.account(id);
    }

    @GetMapping("/{id}/balance")
    BalanceResponse balance(@PathVariable UUID id) {
        return accountUseCases.balance(id);
    }

    @GetMapping("/lookup")
    AccountRecord lookup(@RequestParam(required = false) String email, @RequestParam(required = false) String phone) {
        return accountUseCases.lookup(email, phone);
    }

    @PostMapping("/{id}/deposit")
    MovementResponse deposit(@PathVariable UUID id, @RequestBody MoneyRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
        return accountUseCases.deposit(id, request, user == null ? null : user.userId());
    }

    @PostMapping("/{id}/withdraw")
    MovementResponse withdraw(@PathVariable UUID id, @RequestBody MoneyRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
        return accountUseCases.withdraw(id, request, user == null ? null : user.userId());
    }

    @GetMapping("/{id}/history")
    ResponseEntity<?> history(
        @PathVariable UUID id,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size
    ) {
        if (page == null) {
            return ResponseEntity.ok(accountUseCases.history(id));
        }
        return ResponseEntity.ok(accountUseCases.historyPaginated(id, page, size));
    }

    @PostMapping("/pin/change")
    void changePin(
        @AuthenticationPrincipal AuthenticatedUser user,
        @RequestBody PinChangeRequest request
    ) {
        if (user == null) {
            throw new com.ewallet.common.DomainException("FORBIDDEN", "User session is required");
        }
        authService.changePin(user.userId(), request.currentPin(), request.newPin());
    }

    public record PinChangeRequest(String currentPin, String newPin) {
    }

    @GetMapping("/{id}/ledger")
    List<LedgerEntryRecord> ledger(@PathVariable UUID id) {
        return accountUseCases.ledger(id);
    }
}
