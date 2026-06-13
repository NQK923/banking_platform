package com.ewallet.account.risk;

import java.util.Map;
import java.util.Optional;

public class RecipientInboundSpikeRule implements RiskRule {
    @Override
    public String code() {
        return "RECIPIENT_INBOUND_SPIKE";
    }

    @Override
    public Optional<RiskReason> evaluate(RiskContext context) {
        int distinctSenders = context.features().recipientDistinctInboundSendersLast1h();
        if (distinctSenders < 10) {
            return Optional.empty();
        }
        return Optional.of(new RiskReason(
            code(),
            30,
            "Recipient received funds from many distinct senders recently",
            Map.of("distinctInboundSendersLast1h", String.valueOf(distinctSenders))
        ));
    }
}
