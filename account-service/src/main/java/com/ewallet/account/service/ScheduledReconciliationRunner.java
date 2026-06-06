package com.ewallet.account.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ScheduledReconciliationRunner {
    private final ReconciliationUseCases reconciliationUseCases;

    ScheduledReconciliationRunner(ReconciliationUseCases reconciliationUseCases) {
        this.reconciliationUseCases = reconciliationUseCases;
    }

    @Scheduled(fixedDelayString = "${banking.reconciliation.fixed-delay-ms:300000}")
    void run() {
        reconciliationUseCases.run();
    }
}
