package com.ewallet.account.service;

import com.ewallet.account.model.AccountRecord;
import com.ewallet.account.model.AdminAccountView;
import com.ewallet.account.model.AuditLogRecord;
import com.ewallet.account.model.WalletTransaction;
import com.ewallet.common.DomainException;
import java.util.ArrayList;
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

    public PageResponse<AdminAccountView> accounts(UUID actorId, int page, int size, String q) {
        UUID actor = requireAdminActor(actorId);
        store.audit("ADMIN", actor, "AdminAccountsViewed", "ADMIN", actor, Map.of(), null);
        String filter = q == null ? "" : q.trim().toLowerCase();
        List<AdminAccountView> values = store.adminAccounts().stream()
            .filter(account -> filter.isBlank() || containsAccountText(account, filter))
            .toList();
        return page(values, page, size);
    }

    public AdminAccountView account(UUID accountId, UUID actorId) {
        requireAdminActor(actorId);
        return store.adminAccount(accountId);
    }

    public List<WalletTransaction> transactions(UUID actorId) {
        UUID actor = requireAdminActor(actorId);
        store.audit("ADMIN", actor, "AdminTransactionsViewed", "ADMIN", actor, Map.of(), null);
        return store.transactions();
    }

    public PageResponse<WalletTransaction> transactions(UUID actorId, int page, int size, String status, String accountId) {
        UUID actor = requireAdminActor(actorId);
        store.audit("ADMIN", actor, "AdminTransactionsViewed", "ADMIN", actor, Map.of(), null);
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        String accountFilter = accountId == null ? "" : accountId.trim().toLowerCase();
        List<WalletTransaction> values = store.transactions().stream()
            .filter(tx -> normalizedStatus.isBlank() || tx.status().name().equals(normalizedStatus))
            .filter(tx -> accountFilter.isBlank()
                || contains(tx.senderId(), accountFilter)
                || contains(tx.receiverId(), accountFilter))
            .toList();
        return page(values, page, size);
    }

    public AccountRecord suspend(UUID accountId, UUID actorId) {
        return store.suspendAccount(accountId, requireAdminActor(actorId));
    }

    public List<AuditLogRecord> auditLogs(UUID actorId) {
        UUID actor = requireAdminActor(actorId);
        store.audit("ADMIN", actor, "AdminAuditViewed", "ADMIN", actor, Map.of(), null);
        return store.auditLogs();
    }

    public PageResponse<AuditLogRecord> auditLogs(UUID actorId, int page, int size) {
        UUID actor = requireAdminActor(actorId);
        store.audit("ADMIN", actor, "AdminAuditViewed", "ADMIN", actor, Map.of(), null);
        return page(store.auditLogs(), page, size);
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

    private boolean containsAccountText(AdminAccountView account, String filter) {
        return contains(account.id(), filter)
            || contains(account.userId(), filter)
            || contains(account.email(), filter)
            || contains(account.phoneNumber(), filter)
            || contains(account.code(), filter)
            || contains(account.currency(), filter)
            || contains(account.kind().name(), filter)
            || contains(account.status().name(), filter);
    }

    private boolean contains(Object value, String filter) {
        return value != null && value.toString().toLowerCase().contains(filter);
    }

    private <T> PageResponse<T> page(List<T> values, int requestedPage, int requestedSize) {
        int page = Math.max(0, requestedPage);
        int size = Math.min(100, Math.max(1, requestedSize));
        int totalElements = values.size();
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil(totalElements / (double) size);
        int fromIndex = Math.min(page * size, totalElements);
        int toIndex = Math.min(fromIndex + size, totalElements);
        return new PageResponse<>(new ArrayList<>(values.subList(fromIndex, toIndex)), page, size, totalElements, totalPages);
    }

    public record MaintenanceResult(int count) {
    }

    public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
    }
}
