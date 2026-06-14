package com.ewallet.account.service;

import com.ewallet.account.model.AccountRecord;
import com.ewallet.account.model.LedgerEntryRecord;
import com.ewallet.account.model.WalletTransaction;
import com.ewallet.common.AccountStatus;
import com.ewallet.common.DomainException;
import com.ewallet.common.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.ewallet.account.model.UserRecord;
import com.ewallet.account.security.AuthenticatedUser;
import com.ewallet.account.web.PaginatedHistoryResponse;
import org.springframework.stereotype.Service;

@Service
public class AccountUseCases {
    private final WalletStore store;
    private final AuthService authService;

    public AccountUseCases(WalletStore store, AuthService authService) {
        this.store = store;
        this.authService = authService;
    }

    public AccountRecord account(UUID id) {
        return store.account(id);
    }

    public AccountDetailsResponse accountDetails(UUID id, AuthenticatedUser actor) {
        AccountRecord account = store.account(id);
        requireAccountAccess(account, actor);
        return accountDetails(account);
    }

    public AccountDetailsResponse accountDetails(UUID id) {
        return accountDetails(store.account(id));
    }

    private AccountDetailsResponse accountDetails(AccountRecord account) {
        UserRecord user = account.userId() == null
            ? null
            : store.findUser(account.userId()).orElse(null);
        return new AccountDetailsResponse(
            account.id().toString(),
            account.userId() == null ? null : account.userId().toString(),
            user == null ? null : user.email(),
            user == null ? null : user.phone(),
            account.code(),
            account.currency(),
            account.kind().name(),
            account.status().name(),
            account.version(),
            account.createdAt().toString()
        );
    }

    public BalanceResponse balance(UUID accountId, AuthenticatedUser actor) {
        AccountRecord account = store.account(accountId);
        requireAccountAccess(account, actor);
        return balance(account);
    }

    public BalanceResponse balance(UUID accountId) {
        return balance(store.account(accountId));
    }

    private BalanceResponse balance(AccountRecord account) {
        UUID accountId = account.id();
        return new BalanceResponse(accountId.toString(), new Money(store.balance(accountId), account.currency()).asString(), account.currency());
    }

    public AccountRecord lookup(String email, String phone) {
        AccountRecord account = store.lookupAccount(email, phone)
            .orElseThrow(() -> new DomainException("RECIPIENT_NOT_FOUND", "Recipient account was not found"));
        if (account.status() != AccountStatus.ACTIVE) {
            throw new DomainException("RECIPIENT_SUSPENDED", "Recipient account is suspended");
        }
        return account;
    }

    public MovementResponse deposit(UUID accountId, MoneyRequest request, AuthenticatedUser actor) {
        AccountRecord account = store.account(accountId);
        requireOwnAccountOrAdmin(account, actor, "Cannot deposit to another account");
        Money amount = Money.of(request.amount(), account.currency());
        UUID journalId = UUID.randomUUID();
        store.applyBalancedJournalAndAudit(
            journalId,
            amount,
            store.cashClearingAccountId(),
            amount,
            accountId,
            "mock deposit",
            "ACCOUNT",
            accountId,
            "MoneyDeposited",
            isAdmin(actor) ? "ADMIN" : "USER",
            actor.userId(),
            Map.of("amount", amount.asString()),
            journalId
        );
        return new MovementResponse(journalId.toString(), balance(account));
    }

    public MovementResponse withdraw(UUID accountId, MoneyRequest request, AuthenticatedUser actor) {
        AccountRecord account = store.account(accountId);
        requireAuthenticated(actor);
        UserRecord user = store.findUser(actor.userId())
            .orElseThrow(() -> new DomainException("AUTH_INVALID", "Unknown user"));

        if (!user.roles().contains("ROLE_ADMIN")) {
            if (!account.userId().equals(actor.userId())) {
                throw new DomainException("FORBIDDEN", "Cannot withdraw from another account");
            }
            if (request.pin() == null || request.pin().isBlank()) {
                throw new DomainException("PIN_INVALID", "Transaction PIN is required");
            }
            authService.verifyPin(actor.userId(), request.pin());
        } else {
            if (request.pin() != null && !request.pin().isBlank()) {
                authService.verifyPin(actor.userId(), request.pin());
            }
        }

        Money amount = Money.of(request.amount(), account.currency());
        UUID journalId = UUID.randomUUID();
        store.applyBalancedJournalAndAudit(
            journalId,
            amount,
            accountId,
            amount,
            store.cashClearingAccountId(),
            "mock withdraw",
            "ACCOUNT",
            accountId,
            "MoneyWithdrawn",
            isAdmin(actor) ? "ADMIN" : "USER",
            actor.userId(),
            Map.of("amount", amount.asString()),
            journalId
        );
        return new MovementResponse(journalId.toString(), balance(account));
    }

    private void requireAuthenticated(AuthenticatedUser actor) {
        if (actor == null) {
            throw new DomainException("FORBIDDEN", "User session is required");
        }
    }

    private boolean isAdmin(AuthenticatedUser actor) {
        return actor != null && actor.roles().contains("ROLE_ADMIN");
    }

    private void requireAccountAccess(AccountRecord account, AuthenticatedUser actor) {
        requireOwnAccountOrAdmin(account, actor, "Cannot access another account");
    }

    private void requireOwnAccountOrAdmin(AccountRecord account, AuthenticatedUser actor, String message) {
        requireAuthenticated(actor);
        if (isAdmin(actor)) {
            return;
        }
        if (account.userId() == null || !account.userId().equals(actor.userId())) {
            throw new DomainException("FORBIDDEN", message);
        }
    }

    public List<WalletTransaction> history(UUID accountId, AuthenticatedUser actor) {
        AccountRecord account = store.account(accountId);
        requireAccountAccess(account, actor);
        return store.accountTransactions(accountId);
    }

    public PaginatedHistoryResponse historyPaginated(UUID accountId, int page, Integer sizeParam, AuthenticatedUser actor) {
        AccountRecord account = store.account(accountId);
        requireAccountAccess(account, actor);
        int size = sizeParam == null ? 10 : sizeParam;
        if (size <= 0) size = 10;
        if (size > 100) size = 100;
        
        long totalElements = store.countAccountTransactions(accountId);
        int totalPages = (int) Math.ceil((double) totalElements / size);
        
        List<WalletTransaction> items;
        if (page < 0 || page >= totalPages || totalElements == 0) {
            items = List.of();
        } else {
            int offset = page * size;
            items = store.accountTransactionsPaginated(accountId, size, offset);
        }
        
        return new PaginatedHistoryResponse(items, page, size, totalElements, totalPages);
    }

    public List<LedgerEntryRecord> ledger(UUID accountId, AuthenticatedUser actor) {
        AccountRecord account = store.account(accountId);
        requireAccountAccess(account, actor);
        return store.ledger(accountId);
    }

    public record MoneyRequest(String amount, String pin) {
    }

    public record BalanceResponse(String accountId, String balance, String currency) {
    }

    public record AccountDetailsResponse(
        String id,
        String userId,
        String email,
        String phone,
        String code,
        String currency,
        String kind,
        String status,
        long version,
        String createdAt
    ) {
    }

    public record MovementResponse(String journalId, BalanceResponse balance) {
    }
}
