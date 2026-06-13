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
    String failureReason,
    String reviewStatus,
    UUID riskEvaluationId
) {
    public WalletTransaction(
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
        this(
            id, senderId, receiverId, amount, currency, status, idempotencyKey,
            correlationId, createdAt, updatedAt, debitApplied, note, failureReason, null, null
        );
    }

    public WalletTransaction withStatus(TransactionStatus newStatus) {
        return new WalletTransaction(
            id, senderId, receiverId, amount, currency, newStatus, idempotencyKey,
            correlationId, createdAt, Instant.now(), debitApplied, note, failureReason, reviewStatus, riskEvaluationId
        );
    }

    public WalletTransaction withDebitApplied() {
        return new WalletTransaction(
            id, senderId, receiverId, amount, currency, status, idempotencyKey,
            correlationId, createdAt, Instant.now(), true, note, failureReason, reviewStatus, riskEvaluationId
        );
    }

    public WalletTransaction withFailureReason(String failureReason) {
        return new WalletTransaction(
            id, senderId, receiverId, amount, currency, status, idempotencyKey,
            correlationId, createdAt, Instant.now(), debitApplied, note, failureReason, reviewStatus, riskEvaluationId
        );
    }

    public WalletTransaction withReviewStatus(String reviewStatus) {
        return new WalletTransaction(
            id, senderId, receiverId, amount, currency, status, idempotencyKey,
            correlationId, createdAt, Instant.now(), debitApplied, note, failureReason, reviewStatus, riskEvaluationId
        );
    }

    @com.fasterxml.jackson.annotation.JsonProperty("compensated")
    public boolean isCompensated() {
        return (status == com.ewallet.common.TransactionStatus.FAILED || status == com.ewallet.common.TransactionStatus.COMPENSATING) && debitApplied;
    }
}
