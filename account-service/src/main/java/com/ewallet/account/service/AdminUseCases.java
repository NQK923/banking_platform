package com.ewallet.account.service;

import com.ewallet.account.model.AccountRecord;
import com.ewallet.account.model.AuditLogRecord;
import com.ewallet.account.model.WalletTransaction;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AdminUseCases {
    private final WalletStore store;

    public AdminUseCases(WalletStore store) {
        this.store = store;
    }

    public List<AccountRecord> accounts() {
        return store.accounts();
    }

    public List<WalletTransaction> transactions() {
        return store.transactions();
    }

    public AccountRecord suspend(UUID accountId, UUID actorId) {
        return store.suspendAccount(accountId, actorId);
    }

    public List<AuditLogRecord> auditLogs() {
        return store.auditLogs();
    }
}
