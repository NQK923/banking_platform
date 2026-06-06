package com.ewallet.account.model;

import java.time.Instant;
import java.util.UUID;

public record OutboxRecord(
    UUID id,
    UUID eventId,
    UUID aggregateId,
    String eventType,
    String payload,
    UUID correlationId,
    int attempts,
    Instant createdAt
) {
}
