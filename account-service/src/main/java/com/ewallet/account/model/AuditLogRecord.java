package com.ewallet.account.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditLogRecord(
    UUID id,
    String entityType,
    UUID entityId,
    String eventType,
    String actorType,
    UUID actorId,
    Map<String, String> payload,
    UUID correlationId,
    Instant createdAt
) {
}
