package com.ewallet.account.web;

import com.ewallet.account.model.AccountRecord;
import com.ewallet.account.model.AuditLogRecord;
import com.ewallet.account.model.WalletTransaction;
import com.ewallet.account.security.AuthenticatedUser;
import com.ewallet.account.service.AdminUseCases;
import com.ewallet.account.service.AdminUseCases.MaintenanceResult;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminUseCases adminUseCases;

    public AdminController(AdminUseCases adminUseCases) {
        this.adminUseCases = adminUseCases;
    }

    @GetMapping("/accounts")
    List<AccountRecord> accounts(@AuthenticationPrincipal AuthenticatedUser user) {
        return adminUseCases.accounts(user == null ? null : user.userId());
    }

    @GetMapping("/transactions")
    List<WalletTransaction> transactions(@AuthenticationPrincipal AuthenticatedUser user) {
        return adminUseCases.transactions(user == null ? null : user.userId());
    }

    @PostMapping("/accounts/{id}/suspend")
    AccountRecord suspend(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        return adminUseCases.suspend(id, user == null ? null : user.userId());
    }

    @GetMapping("/audit")
    List<AuditLogRecord> audit(@AuthenticationPrincipal AuthenticatedUser user) {
        return adminUseCases.auditLogs(user == null ? null : user.userId());
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
