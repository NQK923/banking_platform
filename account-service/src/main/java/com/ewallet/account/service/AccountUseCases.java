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
import org.springframework.stereotype.Service;

@Service
public class AccountUseCases {
    private final WalletStore store;

    public AccountUseCases(WalletStore store) {
        this.store = store;
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
        AccountRecord account = store.account(accountId);
        Money amount = Money.of(request.amount(), account.currency());
        UUID journalId = UUID.randomUUID();
        store.applyBalancedJournal(journalId, amount, store.cashClearingAccountId(), amount, accountId, "mock deposit");
        store.audit("ACCOUNT", accountId, "MoneyDeposited", actorId == null ? "SYSTEM" : "ADMIN", actorId, Map.of("amount", amount.asString()), journalId);
        return new MovementResponse(journalId.toString(), balance(accountId));
    }

    public MovementResponse withdraw(UUID accountId, MoneyRequest request, UUID actorId) {
        AccountRecord account = store.account(accountId);
        Money amount = Money.of(request.amount(), account.currency());
        UUID journalId = UUID.randomUUID();
        store.applyBalancedJournal(journalId, amount, accountId, amount, store.cashClearingAccountId(), "mock withdraw");
        store.audit("ACCOUNT", accountId, "MoneyWithdrawn", actorId == null ? "SYSTEM" : "ADMIN", actorId, Map.of("amount", amount.asString()), journalId);
        return new MovementResponse(journalId.toString(), balance(accountId));
    }

    public List<WalletTransaction> history(UUID accountId) {
        return store.accountTransactions(accountId);
    }

    public List<LedgerEntryRecord> ledger(UUID accountId) {
        return store.ledger(accountId);
    }

    public record MoneyRequest(String amount) {
    }

    public record BalanceResponse(String accountId, String balance, String currency) {
    }

    public record MovementResponse(String journalId, BalanceResponse balance) {
    }
}
