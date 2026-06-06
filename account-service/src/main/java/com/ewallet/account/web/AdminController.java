package com.ewallet.account.web;

import com.ewallet.account.model.AccountRecord;
import com.ewallet.account.model.AuditLogRecord;
import com.ewallet.account.model.WalletTransaction;
import com.ewallet.account.security.AuthenticatedUser;
import com.ewallet.account.service.AdminUseCases;
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
    List<AccountRecord> accounts() {
        return adminUseCases.accounts();
    }

    @GetMapping("/transactions")
    List<WalletTransaction> transactions() {
        return adminUseCases.transactions();
    }

    @PostMapping("/accounts/{id}/suspend")
    AccountRecord suspend(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        return adminUseCases.suspend(id, user == null ? null : user.userId());
    }

    @GetMapping("/audit")
    List<AuditLogRecord> audit() {
        return adminUseCases.auditLogs();
    }
}
