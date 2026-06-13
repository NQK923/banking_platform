package com.ewallet.account.risk;

import java.util.Map;
import java.util.Optional;

public class NewRecipientRule implements RiskRule {
    @Override
    public String code() {
        return "NEW_RECIPIENT";
    }

    @Override
    public Optional<RiskReason> evaluate(RiskContext context) {
        if (context.features().hasTransferredToRecipientBefore()) {
            return Optional.empty();
        }
        return Optional.of(new RiskReason(
            code(),
            15,
            "Sender has never transferred to this recipient before",
            Map.of("receiverAccountId", context.receiverAccountId().toString())
        ));
    }
}
