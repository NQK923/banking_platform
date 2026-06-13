package com.ewallet.account.risk;

import java.util.Map;

public record RiskReason(
    String code,
    int weight,
    String message,
    Map<String, String> evidence
) {
}
