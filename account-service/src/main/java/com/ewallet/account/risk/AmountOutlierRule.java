package com.ewallet.account.risk;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

public class AmountOutlierRule implements RiskRule {
    private static final BigDecimal MULTIPLIER = BigDecimal.valueOf(5);

    @Override
    public String code() {
        return "AMOUNT_OUTLIER";
    }

    @Override
    public Optional<RiskReason> evaluate(RiskContext context) {
        BigDecimal median = context.features().medianTransferAmount30d();
        if (median == null || median.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal threshold = median.multiply(MULTIPLIER);
        if (context.amount().compareTo(threshold) <= 0) {
            return Optional.empty();
        }
        return Optional.of(new RiskReason(
            code(),
            25,
            "Amount is significantly higher than sender baseline",
            Map.of(
                "amount", context.amount().toPlainString(),
                "median30d", median.toPlainString(),
                "threshold", threshold.toPlainString()
            )
        ));
    }
}
