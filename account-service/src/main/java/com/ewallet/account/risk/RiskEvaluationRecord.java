package com.ewallet.account.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RiskEvaluationRecord(
    UUID id,
    UUID transactionId,
    UUID senderAccountId,
    UUID receiverAccountId,
    String idempotencyKey,
    String payloadHash,
    BigDecimal amount,
    String currency,
    int riskScore,
    RiskLevel riskLevel,
    RecommendedAction recommendedAction,
    List<RiskReason> reasons,
    Map<String, String> features,
    String modelVersion,
    String policyVersion,
    String decisionStatus,
    UUID traceId,
    Instant createdAt,
    Instant updatedAt
) {
    public RiskDecision toDecision() {
        return new RiskDecision(
            id,
            riskScore,
            riskLevel,
            recommendedAction,
            reasons,
            modelVersion,
            policyVersion,
            createdAt,
            traceId
        );
    }
}
