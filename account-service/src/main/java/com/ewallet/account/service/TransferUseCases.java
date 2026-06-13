package com.ewallet.account.service;

import com.ewallet.account.model.AccountRecord;
import com.ewallet.account.model.WalletTransaction;
import com.ewallet.account.risk.RecommendedAction;
import com.ewallet.account.risk.RiskDecision;
import com.ewallet.account.risk.RiskEvaluationRecord;
import com.ewallet.account.risk.RiskScoringService;
import com.ewallet.account.risk.TransferRiskResponse;
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
    private final TransferMetrics metrics;
    private final FaultInjection faultInjection;
    private final RiskScoringService riskScoringService;

    public TransferUseCases(
        WalletStore store,
        AuthService authService,
        TransferMetrics metrics,
        FaultInjection faultInjection,
        RiskScoringService riskScoringService
    ) {
        this.store = store;
        this.authService = authService;
        this.metrics = metrics;
        this.faultInjection = faultInjection;
        this.riskScoringService = riskScoringService;
    }

    public Object transfer(AuthenticatedUser user, TransferRequest request, String idempotencyHeader) {
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
            .<Object>map(this::resumeIfInFlight)
            .orElseGet(() -> createTransfer(senderId, request, key));
    }

    private Object createTransfer(UUID senderId, TransferRequest request, String key) {
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
        UUID correlationId = UUID.randomUUID();
        RiskDecision risk = riskScoringService.evaluateTransfer(senderId, receiver.id(), amount, request.note(), key, correlationId);
        RecommendedAction action = risk.recommendedAction();
        if (action == RecommendedAction.WARN_USER && !Boolean.TRUE.equals(request.riskAcknowledged())) {
            store.audit("RISK_EVALUATION", risk.riskEvaluationId(), "RiskWarningRequired", "SYSTEM", null, riskPayload(risk), risk.traceId());
            return TransferRiskResponse.fromDecision(
                "RISK_WARNING_REQUIRED",
                risk,
                null,
                "Review this transfer carefully and acknowledge the warning before continuing."
            );
        }
        if (action == RecommendedAction.WARN_USER) {
            requireMatchingRiskEvaluation(request, risk);
            store.updateRiskDecisionStatus(risk.riskEvaluationId(), "USER_ACKNOWLEDGED");
            store.audit("RISK_EVALUATION", risk.riskEvaluationId(), "RiskWarningAcknowledged", "USER", sender.userId(), riskPayload(risk), risk.traceId());
            return createPendingAndMaybeRunSaga(senderId, receiver.id(), amount, request, key, risk, "WARNING_ACKNOWLEDGED", true);
        }
        if (action == RecommendedAction.STEP_UP_AUTH && (request.stepUpPin() == null || request.stepUpPin().isBlank())) {
            store.audit("RISK_EVALUATION", risk.riskEvaluationId(), "RiskStepUpRequired", "SYSTEM", null, riskPayload(risk), risk.traceId());
            return TransferRiskResponse.fromDecision(
                "RISK_STEP_UP_REQUIRED",
                risk,
                null,
                "Additional verification is required before this transfer can continue."
            );
        }
        if (action == RecommendedAction.STEP_UP_AUTH) {
            requireMatchingRiskEvaluation(request, risk);
            authService.verifyPin(sender.userId(), request.stepUpPin());
            store.updateRiskDecisionStatus(risk.riskEvaluationId(), "STEP_UP_PASSED");
            store.audit("RISK_EVALUATION", risk.riskEvaluationId(), "RiskStepUpPassed", "USER", sender.userId(), riskPayload(risk), risk.traceId());
            return createPendingAndMaybeRunSaga(senderId, receiver.id(), amount, request, key, risk, "STEP_UP_PASSED", true);
        }
        if (action == RecommendedAction.MANUAL_REVIEW) {
            WalletTransaction transaction = createPendingAndMaybeRunSaga(
                senderId,
                receiver.id(),
                amount,
                request,
                key,
                risk,
                "MANUAL_REVIEW_REQUIRED",
                false
            );
            store.updateRiskDecisionStatus(risk.riskEvaluationId(), "MANUAL_REVIEW_REQUIRED");
            store.audit("RISK_EVALUATION", risk.riskEvaluationId(), "RiskManualReviewRequired", "SYSTEM", null, riskPayload(risk), risk.traceId());
            return TransferRiskResponse.fromDecision(
                "RISK_MANUAL_REVIEW_REQUIRED",
                risk,
                transaction.id(),
                "This transfer requires manual review. Your money has not been debited."
            );
        }
        if (action == RecommendedAction.BLOCK) {
            store.updateRiskDecisionStatus(risk.riskEvaluationId(), "BLOCKED");
            store.audit("RISK_EVALUATION", risk.riskEvaluationId(), "RiskTransferBlocked", "SYSTEM", null, riskPayload(risk), risk.traceId());
            return TransferRiskResponse.fromDecision("RISK_BLOCKED", risk, null, "This transfer was blocked by risk policy.");
        }
        return createPendingAndMaybeRunSaga(senderId, receiver.id(), amount, request, key, risk, "NONE", true);
    }

    private WalletTransaction createPendingAndMaybeRunSaga(
        UUID senderId,
        UUID receiverId,
        Money amount,
        TransferRequest request,
        String key,
        RiskDecision risk,
        String reviewStatus,
        boolean runImmediately
    ) {
        UUID txId = UUID.randomUUID();
        WalletTransaction transaction = new WalletTransaction(
            txId, senderId, receiverId, amount.amount(), amount.currency(), TransactionStatus.PENDING,
            key, risk.traceId(), Instant.now(), Instant.now(), false, request.note(), null, reviewStatus, risk.riskEvaluationId()
        );
        store.saveTransactionAndAudit(
            transaction,
            "TRANSACTION",
            txId,
            "TransferInitiated",
            "USER",
            store.account(senderId).userId(),
            Map.of(
                "amount", amount.asString(),
                "riskEvaluationId", risk.riskEvaluationId().toString(),
                "riskLevel", risk.riskLevel().name(),
                "recommendedAction", risk.recommendedAction().name()
            ),
            transaction.correlationId()
        );
        store.linkRiskEvaluationToTransaction(risk.riskEvaluationId(), txId, reviewStatus == null || "NONE".equals(reviewStatus) ? "EVALUATED" : riskDecisionStatus(reviewStatus));
        if (runImmediately) {
            CompletableFuture.runAsync(() -> runSaga(txId));
        }
        return transaction;
    }

    private void runSaga(UUID txId) {
        WalletTransaction tx = store.findTransaction(txId).orElseThrow();
        if (tx.status() == TransactionStatus.COMPLETED || tx.status() == TransactionStatus.FAILED || tx.status() == TransactionStatus.CANCELLED) {
            return;
        }
        Money amount = new Money(tx.amount(), tx.currency());
        if (!tx.debitApplied()) {
            try {
                WalletTransaction debited = tx.withDebitApplied();
                store.applyBalancedJournalSaveTransactionAndAudit(
                    tx.id(),
                    amount,
                    tx.senderId(),
                    amount,
                    store.systemSuspenseAccountId(),
                    "transfer debit",
                    debited,
                    "TRANSACTION",
                    tx.id(),
                    "MoneyDebited",
                    "SYSTEM",
                    null,
                    Map.of("amount", amount.asString()),
                    tx.correlationId()
                );
                tx = debited;
            } catch (DomainException ex) {
                String reason = ex.code() + ": " + ex.getMessage();
                WalletTransaction failed = tx.withStatus(TransactionStatus.FAILED).withFailureReason(reason);
                store.saveTransactionAndAudit(
                    failed,
                    "TRANSACTION",
                    tx.id(),
                    "MoneyDebitFailed",
                    "SYSTEM",
                    null,
                    Map.of("code", ex.code()),
                    tx.correlationId()
                );
                store.audit("TRANSACTION", tx.id(), "TransferFailed", "SYSTEM", null, Map.of("code", ex.code()), tx.correlationId());
                metrics.recordFailed(failed);
                return;
            }
        }

        if (tx.status() == TransactionStatus.COMPENSATING) {
            compensate(tx, amount, "SAGA_REPLAY");
            return;
        }

        sleepBeforeCredit();
        AccountRecord receiver = store.account(tx.receiverId());
        if (receiver.status() != AccountStatus.ACTIVE) {
            compensate(tx, amount, "RECIPIENT_SUSPENDED");
            return;
        }

        try {
            faultInjection.maybeFail(FaultInjection.CREDIT_STEP);
            WalletTransaction completed = tx.withStatus(TransactionStatus.COMPLETED);
            store.applyBalancedJournalSaveTransactionAndAudit(
                tx.id(),
                amount,
                store.systemSuspenseAccountId(),
                amount,
                tx.receiverId(),
                "transfer credit",
                completed,
                "TRANSACTION",
                tx.id(),
                "MoneyCredited",
                "SYSTEM",
                null,
                Map.of("amount", amount.asString()),
                tx.correlationId()
            );
            store.audit("TRANSACTION", tx.id(), "TransferCompleted", "SYSTEM", null, Map.of("status", "COMPLETED"), tx.correlationId());
            metrics.recordCompleted(completed);
        } catch (DomainException ex) {
            compensate(tx, amount, ex.code());
        }
    }

    private void compensate(WalletTransaction tx, Money amount, String reason) {
        String failureReason = reason.contains(":") ? reason : reason + ": Credit failed, transaction compensated";
        WalletTransaction compensating = tx.withStatus(TransactionStatus.COMPENSATING);
        metrics.recordCompensating();
        store.saveTransactionAndAudit(
            compensating,
            "TRANSACTION",
            tx.id(),
            "MoneyCreditFailed",
            "SYSTEM",
            null,
            Map.of("code", reason.contains(":") ? reason.split(":")[0].trim() : reason),
            tx.correlationId()
        );
        WalletTransaction failed = compensating.withStatus(TransactionStatus.FAILED).withFailureReason(failureReason);
        store.applyBalancedJournalSaveTransactionAndAudit(
            tx.id(),
            amount,
            store.systemSuspenseAccountId(),
            amount,
            tx.senderId(),
            "transfer compensation",
            failed,
            "TRANSACTION",
            tx.id(),
            "MoneyDebitReversed",
            "SYSTEM",
            null,
            Map.of("amount", amount.asString()),
            tx.correlationId()
        );
        store.audit("TRANSACTION", tx.id(), "TransferFailed", "SYSTEM", null, Map.of("code", reason.contains(":") ? reason.split(":")[0].trim() : reason, "refunded", "true"), tx.correlationId());
        metrics.recordFailed(failed);
    }

    private void sleepBeforeCredit() {
        try {
            Thread.sleep(CREDIT_DELAY_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private WalletTransaction resumeIfInFlight(WalletTransaction transaction) {
        if ("MANUAL_REVIEW_REQUIRED".equals(transaction.reviewStatus()) || "MANUAL_REJECTED".equals(transaction.reviewStatus())) {
            return transaction;
        }
        if (transaction.status() == TransactionStatus.PENDING || transaction.status() == TransactionStatus.COMPENSATING) {
            CompletableFuture.runAsync(() -> runSaga(transaction.id()));
        }
        return transaction;
    }

    public WalletTransaction get(UUID id) {
        return store.findTransaction(id).orElseThrow(() -> new DomainException("TRANSACTION_NOT_FOUND", "Transaction not found"));
    }

    public java.util.List<WalletTransaction> list() {
        return store.transactions();
    }

    public WalletTransaction cancel(UUID id, AuthenticatedUser user) {
        WalletTransaction tx = get(id);
        if (tx.debitApplied()) {
            throw new DomainException("CANCEL_NOT_ALLOWED", "Cannot cancel after debit");
        }
        if (user == null || (!tx.senderId().equals(user.accountId()) && !user.roles().contains("ROLE_ADMIN"))) {
            throw new DomainException("FORBIDDEN", "Cannot cancel another account's transfer");
        }
        WalletTransaction cancelled = tx.withStatus(TransactionStatus.CANCELLED);
        store.saveTransaction(cancelled);
        store.audit("TRANSACTION", id, "TransferCancelled", "USER", user.userId(), Map.of("status", "CANCELLED"), tx.correlationId());
        return cancelled;
    }

    public WalletTransaction approveRiskReview(UUID riskEvaluationId, String reason, AuthenticatedUser user) {
        requireAdmin(user);
        RiskEvaluationRecord risk = store.riskEvaluation(riskEvaluationId);
        UUID txId = risk.transactionId();
        if (txId == null) {
            throw new DomainException("RISK_TRANSACTION_NOT_FOUND", "Risk evaluation is not linked to a transaction");
        }
        WalletTransaction tx = get(txId);
        if (!"MANUAL_REVIEW_REQUIRED".equals(tx.reviewStatus())) {
            throw new DomainException("RISK_REVIEW_NOT_REQUIRED", "Transaction is not waiting for manual review");
        }
        WalletTransaction approved = tx.withReviewStatus("MANUAL_APPROVED");
        store.saveTransaction(approved);
        store.updateRiskDecisionStatus(riskEvaluationId, "MANUAL_APPROVED");
        store.saveRiskReviewAction(riskEvaluationId, txId, "APPROVE", reason, user.userId(), "ROLE_ADMIN", tx.correlationId());
        store.audit("RISK_EVALUATION", riskEvaluationId, "RiskManualApproved", "ADMIN", user.userId(), Map.of("reason", safe(reason)), tx.correlationId());
        CompletableFuture.runAsync(() -> runSaga(txId));
        return approved;
    }

    public WalletTransaction rejectRiskReview(UUID riskEvaluationId, String reason, AuthenticatedUser user) {
        requireAdmin(user);
        RiskEvaluationRecord risk = store.riskEvaluation(riskEvaluationId);
        UUID txId = risk.transactionId();
        if (txId == null) {
            throw new DomainException("RISK_TRANSACTION_NOT_FOUND", "Risk evaluation is not linked to a transaction");
        }
        WalletTransaction tx = get(txId);
        if (tx.debitApplied()) {
            throw new DomainException("RISK_REJECT_NOT_ALLOWED", "Cannot reject after debit");
        }
        WalletTransaction rejected = tx.withReviewStatus("MANUAL_REJECTED")
            .withStatus(TransactionStatus.FAILED)
            .withFailureReason("RISK_MANUAL_REJECTED: " + safe(reason));
        store.saveTransaction(rejected);
        store.updateRiskDecisionStatus(riskEvaluationId, "MANUAL_REJECTED");
        store.saveRiskReviewAction(riskEvaluationId, txId, "REJECT", reason, user.userId(), "ROLE_ADMIN", tx.correlationId());
        store.audit("RISK_EVALUATION", riskEvaluationId, "RiskManualRejected", "ADMIN", user.userId(), Map.of("reason", safe(reason)), tx.correlationId());
        return rejected;
    }

    private void requireMatchingRiskEvaluation(TransferRequest request, RiskDecision risk) {
        if (request.riskEvaluationId() == null || !risk.riskEvaluationId().toString().equals(request.riskEvaluationId())) {
            throw new DomainException("RISK_EVALUATION_REQUIRED", "Matching risk evaluation ID is required");
        }
    }

    private void requireAdmin(AuthenticatedUser user) {
        if (user == null || !user.roles().contains("ROLE_ADMIN")) {
            throw new DomainException("FORBIDDEN", "Admin actor is required");
        }
    }

    private Map<String, String> riskPayload(RiskDecision risk) {
        return Map.of(
            "riskScore", String.valueOf(risk.riskScore()),
            "riskLevel", risk.riskLevel().name(),
            "recommendedAction", risk.recommendedAction().name(),
            "reasonCodes", String.join(",", risk.reasons().stream().map(reason -> reason.code()).toList())
        );
    }

    private String riskDecisionStatus(String reviewStatus) {
        return switch (reviewStatus) {
            case "WARNING_ACKNOWLEDGED" -> "USER_ACKNOWLEDGED";
            case "STEP_UP_PASSED" -> "STEP_UP_PASSED";
            case "MANUAL_REVIEW_REQUIRED" -> "MANUAL_REVIEW_REQUIRED";
            case "MANUAL_APPROVED" -> "MANUAL_APPROVED";
            case "MANUAL_REJECTED" -> "MANUAL_REJECTED";
            case "BLOCKED" -> "BLOCKED";
            default -> "EVALUATED";
        };
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record TransferRequest(
        String senderAccountId,
        String recipientEmail,
        String recipientPhone,
        String amount,
        String idempotencyKey,
        String pin,
        String note,
        String riskEvaluationId,
        Boolean riskAcknowledged,
        String stepUpPin
    ) {
        public TransferRequest(
            String senderAccountId,
            String recipientEmail,
            String recipientPhone,
            String amount,
            String idempotencyKey,
            String pin,
            String note
        ) {
            this(senderAccountId, recipientEmail, recipientPhone, amount, idempotencyKey, pin, note, null, null, null);
        }
    }
}
