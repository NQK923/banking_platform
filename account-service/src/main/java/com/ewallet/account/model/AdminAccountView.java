package com.ewallet.account.model;

import com.ewallet.common.AccountKind;
import com.ewallet.common.AccountStatus;
import java.time.Instant;
import java.util.UUID;

public record AdminAccountView(
    UUID id,
    UUID userId,
    String email,
    String phoneNumber,
    String code,
    String currency,
    AccountKind kind,
    AccountStatus status,
    String balance,
    Instant createdAt
) {
}
