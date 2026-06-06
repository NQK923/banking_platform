package com.ewallet.account.web;

import com.ewallet.account.model.AuditLogRecord;
import com.ewallet.account.security.AuthenticatedUser;
import com.ewallet.account.service.AdminUseCases;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AdminUseCases adminUseCases;

    public AuditController(AdminUseCases adminUseCases) {
        this.adminUseCases = adminUseCases;
    }

    @GetMapping
    List<AuditLogRecord> audit(@AuthenticationPrincipal AuthenticatedUser user) {
        return adminUseCases.auditLogs(user == null ? null : user.userId());
    }
}
