package com.ewallet.account.model;

import com.ewallet.common.AccountKind;
import com.ewallet.common.AccountStatus;
import java.time.Instant;
import java.util.UUID;

public record AccountRecord(
    UUID id,
    UUID userId,
    String code,
    String currency,
    AccountKind kind,
    AccountStatus status,
    long version,
    Instant createdAt
) {
    public AccountRecord withStatus(AccountStatus newStatus) {
        return new AccountRecord(id, userId, code, currency, kind, newStatus, version, createdAt);
    }

    public AccountRecord nextVersion() {
        return new AccountRecord(id, userId, code, currency, kind, status, version + 1, createdAt);
    }
}
