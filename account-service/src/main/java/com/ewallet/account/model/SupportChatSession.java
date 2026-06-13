package com.ewallet.account.model;

import java.time.Instant;
import java.util.UUID;

public record SupportChatSession(
    UUID id,
    UUID userId,
    String status,
    String topic,
    UUID relatedTransactionId,
    Instant createdAt,
    Instant updatedAt
) {
}
