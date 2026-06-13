package com.ewallet.account.model;

import java.time.Instant;
import java.util.UUID;

public record SupportCaseHandoff(
    UUID id,
    UUID sessionId,
    UUID assignedAdminId,
    String reason,
    String summary,
    String status,
    Instant createdAt,
    Instant updatedAt,
    Instant resolvedAt
) {
}
