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

    public BalanceResponse balance(UUID accountId) {
        AccountRecord account = store.account(accountId);
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

    public MovementResponse deposit(UUID accountId, MoneyRequest request, UUID actorId) {
        UUID actor = requireAdminActor(actorId);
        AccountRecord account = store.account(accountId);
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
            "ADMIN",
            actor,
            Map.of("amount", amount.asString()),
            journalId
        );
        return new MovementResponse(journalId.toString(), balance(accountId));
    }

    public MovementResponse withdraw(UUID accountId, MoneyRequest request, UUID actorId) {
        UUID actor = requireAdminActor(actorId);
        AccountRecord account = store.account(accountId);
        
        UserRecord user = store.findUser(actorId)
            .orElseThrow(() -> new DomainException("AUTH_INVALID", "Unknown user"));

        if (!user.roles().contains("ROLE_ADMIN")) {
            if (!account.userId().equals(actorId)) {
                throw new DomainException("FORBIDDEN", "Cannot withdraw from another account");
            }
            if (request.pin() == null || request.pin().isBlank()) {
                throw new DomainException("PIN_INVALID", "Transaction PIN is required");
            }
            authService.verifyPin(actorId, request.pin());
        } else {
            if (request.pin() != null && !request.pin().isBlank()) {
                authService.verifyPin(actorId, request.pin());
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
            "ADMIN",
            actor,
            Map.of("amount", amount.asString()),
            journalId
        );
        return new MovementResponse(journalId.toString(), balance(accountId));
    }

    private UUID requireAdminActor(UUID actorId) {
        if (actorId == null) {
            throw new DomainException("FORBIDDEN", "Admin actor is required");
        }
        return actorId;
    }

    public List<WalletTransaction> history(UUID accountId) {
        return store.accountTransactions(accountId);
    }

    public PaginatedHistoryResponse historyPaginated(UUID accountId, int page, Integer sizeParam) {
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

    public List<LedgerEntryRecord> ledger(UUID accountId) {
        return store.ledger(accountId);
    }

    public record MoneyRequest(String amount, String pin) {
    }

    public record BalanceResponse(String accountId, String balance, String currency) {
    }

    public record MovementResponse(String journalId, BalanceResponse balance) {
    }
}
