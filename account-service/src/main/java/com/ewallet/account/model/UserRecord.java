package com.ewallet.account.model;

import com.ewallet.common.AccountStatus;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record UserRecord(
    UUID id,
    String email,
    String phone,
    String passwordHash,
    String pinHash,
    String refreshTokenHash,
    Set<String> roles,
    AccountStatus status,
    Instant createdAt,
    int failedPinAttempts,
    Instant pinLockedUntil
) {
    public UserRecord withRefreshTokenHash(String hash) {
        return new UserRecord(id, email, phone, passwordHash, pinHash, hash, roles, status, createdAt, failedPinAttempts, pinLockedUntil);
    }

    public UserRecord withStatus(AccountStatus newStatus) {
        return new UserRecord(id, email, phone, passwordHash, pinHash, refreshTokenHash, roles, newStatus, createdAt, failedPinAttempts, pinLockedUntil);
    }
}
