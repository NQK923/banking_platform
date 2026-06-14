package com.ewallet.account.model;

import java.time.Instant;
import java.util.UUID;

public record PasswordResetOtpRecord(
    UUID id,
    UUID userId,
    String identifier,
    String otpHash,
    int attempts,
    Instant expiresAt,
    Instant consumedAt,
    Instant createdAt
) {
}
