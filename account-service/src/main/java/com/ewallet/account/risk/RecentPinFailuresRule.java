package com.ewallet.account.risk;

import java.util.Map;
import java.util.Optional;

public class RecentPinFailuresRule implements RiskRule {
    @Override
    public String code() {
        return "RECENT_PIN_FAILURES";
    }

    @Override
    public Optional<RiskReason> evaluate(RiskContext context) {
        int failures = context.features().senderFailedPinAttempts();
        if (failures < 3) {
            return Optional.empty();
        }
        return Optional.of(new RiskReason(
            code(),
            30,
            "Multiple failed PIN attempts were observed recently",
            Map.of("failedPinAttempts", String.valueOf(failures))
        ));
    }
}
