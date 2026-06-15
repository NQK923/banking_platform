package com.ewallet.account.web;

import com.ewallet.account.model.AccountRecord;
import com.ewallet.account.model.LedgerEntryRecord;
import com.ewallet.account.model.WalletTransaction;
import com.ewallet.account.service.AccountUseCases;
import com.ewallet.account.service.AccountUseCases.AccountDetailsResponse;
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
import org.springframework.web.bind.annotation.RequestHeader;
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
    AccountDetailsResponse get(@PathVariable("id") UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        return accountUseCases.accountDetails(id, user);
    }

    @GetMapping("/{id}/balance")
    BalanceResponse balance(@PathVariable("id") UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        return accountUseCases.balance(id, user);
    }

    @GetMapping("/lookup")
    AccountDetailsResponse lookup(@RequestParam(value = "email", required = false) String email, @RequestParam(value = "phone", required = false) String phone) {
        return accountUseCases.lookup(email, phone);
    }

    @PostMapping("/{id}/deposit")
    MovementResponse deposit(
        @PathVariable("id") UUID id,
        @RequestBody MoneyRequest request,
        @AuthenticationPrincipal AuthenticatedUser user,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return accountUseCases.deposit(id, request, user, idempotencyKey);
    }

    @PostMapping("/{id}/withdraw")
    MovementResponse withdraw(
        @PathVariable("id") UUID id,
        @RequestBody MoneyRequest request,
        @AuthenticationPrincipal AuthenticatedUser user,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return accountUseCases.withdraw(id, request, user, idempotencyKey);
    }

    @GetMapping("/{id}/history")
    ResponseEntity<?> history(
        @PathVariable("id") UUID id,
        @RequestParam(value = "page", required = false) Integer page,
        @RequestParam(value = "size", required = false) Integer size,
        @AuthenticationPrincipal AuthenticatedUser user
    ) {
        if (page == null) {
            return ResponseEntity.ok(accountUseCases.history(id, user));
        }
        return ResponseEntity.ok(accountUseCases.historyPaginated(id, page, size, user));
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
    List<LedgerEntryRecord> ledger(@PathVariable("id") UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        return accountUseCases.ledger(id, user);
    }
}
