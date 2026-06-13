package com.ewallet.account.risk;

import java.util.Map;
import java.util.Optional;

public class SuspiciousNoteRule implements RiskRule {
    @Override
    public String code() {
        return "SUSPICIOUS_NOTE";
    }

    @Override
    public Optional<RiskReason> evaluate(RiskContext context) {
        if (!context.features().suspiciousNote()) {
            return Optional.empty();
        }
        return Optional.of(new RiskReason(
            code(),
            10,
            "Transfer note contains suspicious wording",
            Map.of("matched", "true")
        ));
    }
}
