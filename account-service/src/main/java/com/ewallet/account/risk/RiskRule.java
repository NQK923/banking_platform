package com.ewallet.account.risk;

import java.util.Optional;

public interface RiskRule {
    String code();

    Optional<RiskReason> evaluate(RiskContext context);
}
