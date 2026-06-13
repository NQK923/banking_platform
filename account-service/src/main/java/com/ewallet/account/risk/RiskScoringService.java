package com.ewallet.account.risk;

import com.ewallet.account.service.WalletStore;
import com.ewallet.common.DomainException;
import com.ewallet.common.Money;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RiskScoringService {
    public static final String MODEL_VERSION = "rules-v1.0.0";
    public static final String POLICY_VERSION = "risk-policy-v1.0.0";
    private static final String FEATURE_VERSION = "risk-features-v1.0.0";
    private static final int EVALUATION_TTL_MINUTES = 10;

    private final WalletStore store;
    private final List<RiskRule> rules = List.of(
        new NewRecipientRule(),
        new AmountOutlierRule(),
        new RecentPinFailuresRule(),
        new RecipientInboundSpikeRule(),
        new SuspiciousNoteRule()
    );

    public RiskScoringService(WalletStore store) {
        this.store = store;
    }

    public RiskDecision evaluateTransfer(
        UUID senderAccountId,
        UUID receiverAccountId,
        Money amount,
        String note,
        String idempotencyKey,
        UUID traceId
    ) {
        String payloadHash = payloadHash(senderAccountId, receiverAccountId, amount, note);
        return store.findLatestRiskEvaluation(senderAccountId, idempotencyKey)
            .map(existing -> reuseOrReject(existing, payloadHash))
            .orElseGet(() -> createEvaluation(senderAccountId, receiverAccountId, amount, note, idempotencyKey, payloadHash, traceId));
    }

    private RiskDecision reuseOrReject(RiskEvaluationRecord existing, String payloadHash) {
        if (!existing.payloadHash().equals(payloadHash)) {
            throw new DomainException("IDEMPOTENCY_PAYLOAD_MISMATCH", "Idempotency key was reused with a different transfer payload");
        }
        if (existing.createdAt().plusSeconds(EVALUATION_TTL_MINUTES * 60L).isBefore(Instant.now())) {
            throw new DomainException("RISK_EVALUATION_EXPIRED", "Risk evaluation expired; start a new transfer");
        }
        return existing.toDecision();
    }

    private RiskDecision createEvaluation(
        UUID senderAccountId,
        UUID receiverAccountId,
        Money amount,
        String note,
        String idempotencyKey,
        String payloadHash,
        UUID traceId
    ) {
        Instant now = Instant.now();
        RiskFeatures features = store.riskFeatures(senderAccountId, receiverAccountId, note);
        RiskContext context = new RiskContext(senderAccountId, receiverAccountId, amount.amount(), amount.currency(), note, features, now);
        List<RiskReason> reasons = rules.stream()
            .map(rule -> rule.evaluate(context))
            .flatMap(java.util.Optional::stream)
            .toList();
        int score = Math.min(100, reasons.stream().mapToInt(RiskReason::weight).sum());
        RiskLevel level = classify(score);
        RecommendedAction action = actionFor(level);
        UUID id = UUID.randomUUID();
        Map<String, String> featureMap = featureMap(features);
        RiskEvaluationRecord record = new RiskEvaluationRecord(
            id,
            null,
            senderAccountId,
            receiverAccountId,
            idempotencyKey,
            payloadHash,
            amount.amount(),
            amount.currency(),
            score,
            level,
            action,
            reasons,
            featureMap,
            MODEL_VERSION,
            POLICY_VERSION,
            initialDecisionStatus(action),
            traceId,
            now,
            now
        );
        store.saveRiskEvaluation(record, FEATURE_VERSION);
        return record.toDecision();
    }

    private RiskLevel classify(int score) {
        if (score >= 80) {
            return RiskLevel.CRITICAL;
        }
        if (score >= 60) {
            return RiskLevel.HIGH;
        }
        if (score >= 30) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    private RecommendedAction actionFor(RiskLevel level) {
        return switch (level) {
            case LOW -> RecommendedAction.ALLOW;
            case MEDIUM -> RecommendedAction.WARN_USER;
            case HIGH -> RecommendedAction.STEP_UP_AUTH;
            case CRITICAL -> RecommendedAction.MANUAL_REVIEW;
        };
    }

    private String initialDecisionStatus(RecommendedAction action) {
        return switch (action) {
            case STEP_UP_AUTH -> "STEP_UP_REQUIRED";
            case MANUAL_REVIEW -> "MANUAL_REVIEW_REQUIRED";
            case BLOCK -> "BLOCKED";
            default -> "EVALUATED";
        };
    }

    private Map<String, String> featureMap(RiskFeatures features) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("hasTransferredToRecipientBefore", String.valueOf(features.hasTransferredToRecipientBefore()));
        BigDecimal median = features.medianTransferAmount30d();
        values.put("medianTransferAmount30d", median == null ? "" : median.toPlainString());
        values.put("senderTransfersLast10m", String.valueOf(features.senderTransfersLast10m()));
        values.put("senderFailedPinAttempts", String.valueOf(features.senderFailedPinAttempts()));
        values.put("recipientDistinctInboundSendersLast1h", String.valueOf(features.recipientDistinctInboundSendersLast1h()));
        values.put("suspiciousNote", String.valueOf(features.suspiciousNote()));
        return values;
    }

    private String payloadHash(UUID senderAccountId, UUID receiverAccountId, Money amount, String note) {
        String payload = senderAccountId + "|" + receiverAccountId + "|" + amount.asString() + "|" + amount.currency() + "|" + (note == null ? "" : note);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
