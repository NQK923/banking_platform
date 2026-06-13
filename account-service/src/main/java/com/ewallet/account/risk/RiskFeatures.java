package com.ewallet.account.risk;

import java.math.BigDecimal;

public record RiskFeatures(
    boolean hasTransferredToRecipientBefore,
    BigDecimal medianTransferAmount30d,
    int senderTransfersLast10m,
    int senderFailedPinAttempts,
    int recipientDistinctInboundSendersLast1h,
    boolean suspiciousNote
) {
}
