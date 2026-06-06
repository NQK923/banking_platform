package com.ewallet.account.service;

import com.ewallet.account.model.AccountRecord;
import com.ewallet.account.model.WalletTransaction;
import com.ewallet.account.security.AuthenticatedUser;
import com.ewallet.common.AccountStatus;
import com.ewallet.common.DomainException;
import com.ewallet.common.Money;
import com.ewallet.common.TransactionStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Service;

@Service
public class TransferUseCases {
    private static final long CREDIT_DELAY_MILLIS = 250;
    private final WalletStore store;
    private final AuthService authService;

    public TransferUseCases(WalletStore store, AuthService authService) {
        this.store = store;
        this.authService = authService;
    }

    public WalletTransaction transfer(AuthenticatedUser user, TransferRequest request, String idempotencyHeader) {
        UUID senderId = request.senderAccountId() == null ? user.accountId() : UUID.fromString(request.senderAccountId());
        if (!senderId.equals(user.accountId()) && !user.roles().contains("ROLE_ADMIN")) {
            throw new DomainException("FORBIDDEN", "Cannot transfer from another account");
        }
        authService.verifyPin(user.userId(), request.pin());
        String key = idempotencyHeader == null || idempotencyHeader.isBlank() ? request.idempotencyKey() : idempotencyHeader;
        if (key == null || key.isBlank()) {
            throw new DomainException("IDEMPOTENCY_KEY_REQUIRED", "Idempotency key is required");
        }
        return store.findTransactionByIdempotency(senderId, key)
            .orElseGet(() -> createTransfer(senderId, request, key));
    }

    private WalletTransaction createTransfer(UUID senderId, TransferRequest request, String key) {
        AccountRecord sender = store.account(senderId);
        AccountRecord receiver = store.lookupAccount(request.recipientEmail(), request.recipientPhone())
            .orElseThrow(() -> new DomainException("RECIPIENT_NOT_FOUND", "Recipient account was not found"));
        if (senderId.equals(receiver.id())) {
            throw new DomainException("SELF_TRANSFER", "Cannot transfer to the same account");
        }
        if (!sender.currency().equals(receiver.currency())) {
            throw new DomainException("CURRENCY_MISMATCH", "Sender and receiver currency must match");
        }
        if (receiver.status() != AccountStatus.ACTIVE) {
            throw new DomainException("RECIPIENT_SUSPENDED", "Recipient account is suspended");
        }
        Money amount = Money.of(request.amount(), sender.currency());
        if (!amount.isPositive()) {
            throw new DomainException("INVALID_AMOUNT", "Amount must be positive");
        }
        UUID txId = UUID.randomUUID();
        WalletTransaction transaction = new WalletTransaction(
            txId, senderId, receiver.id(), amount.amount(), amount.currency(), TransactionStatus.PENDING,
            key, UUID.randomUUID(), Instant.now(), Instant.now(), false
        );
        store.saveTransaction(transaction);
        store.audit("TRANSACTION", txId, "TransferInitiated", "USER", sender.userId(), Map.of("amount", amount.asString()), transaction.correlationId());
        CompletableFuture.runAsync(() -> runSaga(txId));
        return transaction;
    }

    private void runSaga(UUID txId) {
        WalletTransaction tx = store.findTransaction(txId).orElseThrow();
        Money amount = new Money(tx.amount(), tx.currency());
        try {
            store.applyBalancedJournal(tx.id(), amount, tx.senderId(), amount, store.systemSuspenseAccountId(), "transfer debit");
            tx = tx.withDebitApplied();
            store.saveTransaction(tx);
            store.audit("TRANSACTION", tx.id(), "MoneyDebited", "SYSTEM", null, Map.of("amount", amount.asString()), tx.correlationId());
        } catch (DomainException ex) {
            WalletTransaction failed = tx.withStatus(TransactionStatus.FAILED);
            store.saveTransaction(failed);
            store.audit("TRANSACTION", tx.id(), "MoneyDebitFailed", "SYSTEM", null, Map.of("code", ex.code()), tx.correlationId());
            store.audit("TRANSACTION", tx.id(), "TransferFailed", "SYSTEM", null, Map.of("code", ex.code()), tx.correlationId());
            return;
        }

        sleepBeforeCredit();
        AccountRecord receiver = store.account(tx.receiverId());
        if (receiver.status() != AccountStatus.ACTIVE) {
            compensate(tx, amount, "RECIPIENT_SUSPENDED");
            return;
        }

        try {
            store.applyBalancedJournal(tx.id(), amount, store.systemSuspenseAccountId(), amount, tx.receiverId(), "transfer credit");
            WalletTransaction completed = tx.withStatus(TransactionStatus.COMPLETED);
            store.saveTransaction(completed);
            store.audit("TRANSACTION", tx.id(), "MoneyCredited", "SYSTEM", null, Map.of("amount", amount.asString()), tx.correlationId());
            store.audit("TRANSACTION", tx.id(), "TransferCompleted", "SYSTEM", null, Map.of("status", "COMPLETED"), tx.correlationId());
        } catch (DomainException ex) {
            compensate(tx, amount, ex.code());
        }
    }

    private void compensate(WalletTransaction tx, Money amount, String code) {
        WalletTransaction compensating = tx.withStatus(TransactionStatus.COMPENSATING);
        store.saveTransaction(compensating);
        store.audit("TRANSACTION", tx.id(), "MoneyCreditFailed", "SYSTEM", null, Map.of("code", code), tx.correlationId());
        store.applyBalancedJournal(tx.id(), amount, store.systemSuspenseAccountId(), amount, tx.senderId(), "transfer compensation");
        WalletTransaction failed = compensating.withStatus(TransactionStatus.FAILED);
        store.saveTransaction(failed);
        store.audit("TRANSACTION", tx.id(), "MoneyDebitReversed", "SYSTEM", null, Map.of("amount", amount.asString()), tx.correlationId());
        store.audit("TRANSACTION", tx.id(), "TransferFailed", "SYSTEM", null, Map.of("code", code, "refunded", "true"), tx.correlationId());
    }

    private void sleepBeforeCredit() {
        try {
            Thread.sleep(CREDIT_DELAY_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public WalletTransaction get(UUID id) {
        return store.findTransaction(id).orElseThrow(() -> new DomainException("TRANSACTION_NOT_FOUND", "Transaction not found"));
    }

    public java.util.List<WalletTransaction> list() {
        return store.transactions();
    }

    public WalletTransaction cancel(UUID id) {
        WalletTransaction tx = get(id);
        if (tx.debitApplied()) {
            throw new DomainException("CANCEL_NOT_ALLOWED", "Cannot cancel after debit");
        }
        WalletTransaction cancelled = tx.withStatus(TransactionStatus.CANCELLED);
        store.saveTransaction(cancelled);
        store.audit("TRANSACTION", id, "TransferCancelled", "USER", null, Map.of("status", "CANCELLED"), tx.correlationId());
        return cancelled;
    }

    public record TransferRequest(
        String senderAccountId,
        String recipientEmail,
        String recipientPhone,
        String amount,
        String idempotencyKey,
        String pin
    ) {
    }
}
