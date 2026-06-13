package com.ewallet.account.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "banking.reconciliation.scheduler.enabled", havingValue = "true", matchIfMissing = true)
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
