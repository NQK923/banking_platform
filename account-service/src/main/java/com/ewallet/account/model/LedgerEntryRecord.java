package com.ewallet.account.model;

import com.ewallet.common.EntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntryRecord(
    UUID id,
    UUID journalId,
    UUID accountId,
    BigDecimal amount,
    String currency,
    EntryType entryType,
    String description,
    Instant createdAt
) {
}
