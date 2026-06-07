package com.ewallet.account.model;

import com.ewallet.common.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WalletTransaction(
    UUID id,
    UUID senderId,
    UUID receiverId,
    BigDecimal amount,
    String currency,
    TransactionStatus status,
    String idempotencyKey,
    UUID correlationId,
    Instant createdAt,
    Instant updatedAt,
    boolean debitApplied,
    String note,
    String failureReason
) {
    public WalletTransaction withStatus(TransactionStatus newStatus) {
        return new WalletTransaction(
            id, senderId, receiverId, amount, currency, newStatus, idempotencyKey,
            correlationId, createdAt, Instant.now(), debitApplied, note, failureReason
        );
    }

    public WalletTransaction withDebitApplied() {
        return new WalletTransaction(
            id, senderId, receiverId, amount, currency, status, idempotencyKey,
            correlationId, createdAt, Instant.now(), true, note, failureReason
        );
    }

    public WalletTransaction withFailureReason(String failureReason) {
        return new WalletTransaction(
            id, senderId, receiverId, amount, currency, status, idempotencyKey,
            correlationId, createdAt, Instant.now(), debitApplied, note, failureReason
        );
    }
}
