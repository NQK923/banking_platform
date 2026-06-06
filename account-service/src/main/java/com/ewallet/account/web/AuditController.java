package com.ewallet.account.web;

import com.ewallet.account.model.AuditLogRecord;
import com.ewallet.account.service.AdminUseCases;
import java.util.List;
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
    List<AuditLogRecord> audit() {
        return adminUseCases.auditLogs();
    }
}
