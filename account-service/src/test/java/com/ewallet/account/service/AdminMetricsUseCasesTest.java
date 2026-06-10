package com.ewallet.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ewallet.account.model.ReconciliationReport;
import com.ewallet.account.model.WalletTransaction;
import com.ewallet.common.TransactionStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminMetricsUseCasesTest {
    @Test
    void returnsRegisteredWalletMetricsForAdminDashboard() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TransferMetrics transferMetrics = new TransferMetrics(registry);
        ReconciliationMetrics reconciliationMetrics = new ReconciliationMetrics(registry);
        AdminMetricsUseCases useCases = new AdminMetricsUseCases(registry, reconciliationMetrics);

        WalletTransaction failed = new WalletTransaction(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            BigDecimal.TEN,
            "VND",
            TransactionStatus.FAILED,
            "idem",
            UUID.randomUUID(),
            Instant.now().minusSeconds(1),
            Instant.now(),
            false,
            null,
            null
        );

        transferMetrics.recordFailed(failed);
        transferMetrics.recordCompensating();
        reconciliationMetrics.record(new ReconciliationReport(
            Instant.now(),
            2,
            false,
            Map.of("VND", BigDecimal.TEN),
            List.of("drift")
        ));

        AdminMetricsUseCases.SystemMetrics metrics = useCases.metrics();

        assertThat(metrics.transferFailedTotal()).isEqualTo(1);
        assertThat(metrics.transferCompensatingTotal()).isEqualTo(1);
        assertThat(metrics.walletSagaLatency()).isGreaterThanOrEqualTo(0);
        assertThat(metrics.walletConsumerLag()).isZero();
        assertThat(metrics.walletDlqDepth()).isZero();
        assertThat(metrics.reconciliationDrift()).isEqualTo(2);
    }
}
