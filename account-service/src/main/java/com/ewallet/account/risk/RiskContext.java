package com.ewallet.account.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RiskContext(
    UUID senderAccountId,
    UUID receiverAccountId,
    BigDecimal amount,
    String currency,
    String note,
    RiskFeatures features,
    Instant evaluatedAt
) {
}
