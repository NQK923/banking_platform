package com.ewallet.account.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RiskRuleTest {
    private final UUID sender = UUID.randomUUID();
    private final UUID receiver = UUID.randomUUID();

    @Test
    void newRecipientRuleTriggersOnlyForUnknownRecipient() {
        Optional<RiskReason> reason = new NewRecipientRule().evaluate(context(features(false, null, 0, 0, 0, false)));
        assertThat(reason).isPresent();
        assertThat(reason.get().code()).isEqualTo("NEW_RECIPIENT");

        assertThat(new NewRecipientRule().evaluate(context(features(true, null, 0, 0, 0, false)))).isEmpty();
    }

    @Test
    void amountOutlierRuleComparesAgainstSenderBaseline() {
        Optional<RiskReason> reason = new AmountOutlierRule().evaluate(context(features(true, new BigDecimal("10.0000"), 0, 0, 0, false)));
        assertThat(reason).isPresent();
        assertThat(reason.get().code()).isEqualTo("AMOUNT_OUTLIER");

        RiskContext normal = new RiskContext(sender, receiver, new BigDecimal("40.0000"), "VND", null, features(true, new BigDecimal("10.0000"), 0, 0, 0, false), Instant.now());
        assertThat(new AmountOutlierRule().evaluate(normal)).isEmpty();
    }

    @Test
    void securityAndRecipientRulesTriggerAtConfiguredThresholds() {
        assertThat(new RecentPinFailuresRule().evaluate(context(features(true, null, 0, 3, 0, false))))
            .hasValueSatisfying(reason -> assertThat(reason.code()).isEqualTo("RECENT_PIN_FAILURES"));
        assertThat(new RecipientInboundSpikeRule().evaluate(context(features(true, null, 0, 0, 10, false))))
            .hasValueSatisfying(reason -> assertThat(reason.code()).isEqualTo("RECIPIENT_INBOUND_SPIKE"));
        assertThat(new SuspiciousNoteRule().evaluate(context(features(true, null, 0, 0, 0, true))))
            .hasValueSatisfying(reason -> assertThat(reason.code()).isEqualTo("SUSPICIOUS_NOTE"));
    }

    private RiskContext context(RiskFeatures features) {
        return new RiskContext(sender, receiver, new BigDecimal("100.0000"), "VND", "urgent", features, Instant.now());
    }

    private RiskFeatures features(
        boolean knownRecipient,
        BigDecimal median,
        int velocity,
        int pinFailures,
        int inboundSenders,
        boolean suspiciousNote
    ) {
        return new RiskFeatures(knownRecipient, median, velocity, pinFailures, inboundSenders, suspiciousNote);
    }
}
