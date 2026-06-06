package com.ewallet.account.web;

import com.ewallet.account.model.ReconciliationReport;
import com.ewallet.account.service.ReconciliationUseCases;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reconciliation")
public class ReconciliationController {
    private final ReconciliationUseCases reconciliationUseCases;

    public ReconciliationController(ReconciliationUseCases reconciliationUseCases) {
        this.reconciliationUseCases = reconciliationUseCases;
    }

    @PostMapping("/run")
    ReconciliationReport run() {
        return reconciliationUseCases.run();
    }
}
