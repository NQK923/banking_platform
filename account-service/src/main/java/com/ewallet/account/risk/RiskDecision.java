package com.ewallet.account.risk;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RiskDecision(
    UUID riskEvaluationId,
    int riskScore,
    RiskLevel riskLevel,
    RecommendedAction recommendedAction,
    List<RiskReason> reasons,
    String modelVersion,
    String policyVersion,
    Instant evaluatedAt,
    UUID traceId
) {
}
