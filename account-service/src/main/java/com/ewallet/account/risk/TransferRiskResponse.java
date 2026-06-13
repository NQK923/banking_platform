package com.ewallet.account.risk;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransferRiskResponse(
    String result,
    UUID riskEvaluationId,
    int riskScore,
    RiskLevel riskLevel,
    RecommendedAction recommendedAction,
    List<RiskReason> reasons,
    String modelVersion,
    String policyVersion,
    Instant evaluatedAt,
    UUID traceId,
    UUID transactionId,
    String message
) {
    public static TransferRiskResponse fromDecision(String result, RiskDecision decision, UUID transactionId, String message) {
        return new TransferRiskResponse(
            result,
            decision.riskEvaluationId(),
            decision.riskScore(),
            decision.riskLevel(),
            decision.recommendedAction(),
            decision.reasons(),
            decision.modelVersion(),
            decision.policyVersion(),
            decision.evaluatedAt(),
            decision.traceId(),
            transactionId,
            message
        );
    }
}
