package com.ewallet.account.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ReconciliationReport(
    Instant checkedAt,
    int driftCount,
    boolean zeroDrift,
    Map<String, BigDecimal> totalByCurrency,
    List<String> findings
) {
}
