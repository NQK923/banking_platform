package com.ewallet.account.service;

import com.ewallet.account.model.AccountRecord;
import com.ewallet.account.model.AuditLogRecord;
import com.ewallet.account.model.WalletTransaction;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AdminUseCases {
    private final WalletStore store;

    public AdminUseCases(WalletStore store) {
        this.store = store;
    }

    public List<AccountRecord> accounts(UUID actorId) {
        store.audit("ADMIN", actorId == null ? UUID.randomUUID() : actorId, "AdminAccountsViewed", "ADMIN", actorId, Map.of(), null);
        return store.accounts();
    }

    public List<WalletTransaction> transactions(UUID actorId) {
        store.audit("ADMIN", actorId == null ? UUID.randomUUID() : actorId, "AdminTransactionsViewed", "ADMIN", actorId, Map.of(), null);
        return store.transactions();
    }

    public AccountRecord suspend(UUID accountId, UUID actorId) {
        return store.suspendAccount(accountId, actorId);
    }

    public List<AuditLogRecord> auditLogs(UUID actorId) {
        store.audit("ADMIN", actorId == null ? UUID.randomUUID() : actorId, "AdminAuditViewed", "ADMIN", actorId, Map.of(), null);
        return store.auditLogs();
    }

    public MaintenanceResult writeSnapshots(UUID actorId) {
        int count = store.writeAccountSnapshots();
        store.audit("ADMIN", actorId == null ? UUID.randomUUID() : actorId, "AccountSnapshotsWritten", "ADMIN", actorId, Map.of("count", String.valueOf(count)), null);
        return new MaintenanceResult(count);
    }

    public MaintenanceResult rebuildBalances(UUID actorId) {
        int count = store.rebuildBalancesFromSnapshotsAndEvents();
        store.audit("ADMIN", actorId == null ? UUID.randomUUID() : actorId, "AccountBalancesRebuilt", "ADMIN", actorId, Map.of("count", String.valueOf(count)), null);
        return new MaintenanceResult(count);
    }

    public record MaintenanceResult(int count) {
    }
}
