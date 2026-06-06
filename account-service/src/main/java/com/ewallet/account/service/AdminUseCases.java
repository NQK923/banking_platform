package com.ewallet.account.service;

import com.ewallet.account.model.AccountRecord;
import com.ewallet.account.model.AuditLogRecord;
import com.ewallet.account.model.WalletTransaction;
import com.ewallet.common.DomainException;
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
        UUID actor = requireAdminActor(actorId);
        store.audit("ADMIN", actor, "AdminAccountsViewed", "ADMIN", actor, Map.of(), null);
        return store.accounts();
    }

    public List<WalletTransaction> transactions(UUID actorId) {
        UUID actor = requireAdminActor(actorId);
        store.audit("ADMIN", actor, "AdminTransactionsViewed", "ADMIN", actor, Map.of(), null);
        return store.transactions();
    }

    public AccountRecord suspend(UUID accountId, UUID actorId) {
        return store.suspendAccount(accountId, requireAdminActor(actorId));
    }

    public List<AuditLogRecord> auditLogs(UUID actorId) {
        UUID actor = requireAdminActor(actorId);
        store.audit("ADMIN", actor, "AdminAuditViewed", "ADMIN", actor, Map.of(), null);
        return store.auditLogs();
    }

    public MaintenanceResult writeSnapshots(UUID actorId) {
        UUID actor = requireAdminActor(actorId);
        int count = store.writeAccountSnapshots();
        store.audit("ADMIN", actor, "AccountSnapshotsWritten", "ADMIN", actor, Map.of("count", String.valueOf(count)), null);
        return new MaintenanceResult(count);
    }

    public MaintenanceResult rebuildBalances(UUID actorId) {
        UUID actor = requireAdminActor(actorId);
        int count = store.rebuildBalancesFromSnapshotsAndEvents();
        store.audit("ADMIN", actor, "AccountBalancesRebuilt", "ADMIN", actor, Map.of("count", String.valueOf(count)), null);
        return new MaintenanceResult(count);
    }

    private UUID requireAdminActor(UUID actorId) {
        if (actorId == null) {
            throw new DomainException("FORBIDDEN", "Admin actor is required");
        }
        return actorId;
    }

    public record MaintenanceResult(int count) {
    }
}
