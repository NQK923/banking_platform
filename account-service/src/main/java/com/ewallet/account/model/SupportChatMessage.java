package com.ewallet.account.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SupportChatMessage(
    UUID id,
    UUID sessionId,
    String senderType,
    String message,
    Map<String, String> metadata,
    Instant createdAt
) {
}
