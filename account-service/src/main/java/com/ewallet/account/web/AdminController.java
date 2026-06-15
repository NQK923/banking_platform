package com.ewallet.account.web;

import com.ewallet.account.model.AccountRecord;
import com.ewallet.account.model.AdminAccountView;
import com.ewallet.account.model.AuditLogRecord;
import com.ewallet.account.model.WalletTransaction;
import com.ewallet.account.security.AuthenticatedUser;
import com.ewallet.account.service.AdminMetricsUseCases;
import com.ewallet.account.service.AdminUseCases;
import com.ewallet.account.service.AdminUseCases.MaintenanceResult;
import com.ewallet.account.service.AdminUseCases.PageResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminUseCases adminUseCases;
    private final AdminMetricsUseCases adminMetricsUseCases;

    public AdminController(AdminUseCases adminUseCases, AdminMetricsUseCases adminMetricsUseCases) {
        this.adminUseCases = adminUseCases;
        this.adminMetricsUseCases = adminMetricsUseCases;
    }

    @GetMapping("/accounts")
    PageResponse<AdminAccountView> accounts(
        @AuthenticationPrincipal AuthenticatedUser user,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "10") int size,
        @RequestParam(value = "q", required = false) String q
    ) {
        return adminUseCases.accounts(user == null ? null : user.userId(), page, size, q);
    }

    @GetMapping("/accounts/{id}")
    AdminAccountView account(@PathVariable("id") UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        return adminUseCases.account(id, user == null ? null : user.userId());
    }

    @GetMapping("/transactions")
    PageResponse<WalletTransaction> transactions(
        @AuthenticationPrincipal AuthenticatedUser user,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "10") int size,
        @RequestParam(value = "status", required = false) String status,
        @RequestParam(value = "accountId", required = false) String accountId
    ) {
        return adminUseCases.transactions(user == null ? null : user.userId(), page, size, status, accountId);
    }

    @PostMapping("/accounts/{id}/suspend")
    AccountRecord suspend(@PathVariable("id") UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        return adminUseCases.suspend(id, user == null ? null : user.userId());
    }

    @GetMapping("/audit")
    PageResponse<AuditLogRecord> audit(
        @AuthenticationPrincipal AuthenticatedUser user,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "15") int size
    ) {
        return adminUseCases.auditLogs(user == null ? null : user.userId(), page, size);
    }

    @GetMapping("/metrics")
    AdminMetricsUseCases.SystemMetrics metrics() {
        return adminMetricsUseCases.metrics();
    }

    @PostMapping("/snapshots/write")
    MaintenanceResult writeSnapshots(@AuthenticationPrincipal AuthenticatedUser user) {
        return adminUseCases.writeSnapshots(user == null ? null : user.userId());
    }

    @PostMapping("/projections/rebuild-balances")
    MaintenanceResult rebuildBalances(@AuthenticationPrincipal AuthenticatedUser user) {
        return adminUseCases.rebuildBalances(user == null ? null : user.userId());
    }
}
