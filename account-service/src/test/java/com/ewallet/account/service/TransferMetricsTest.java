package com.ewallet.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ewallet.account.model.WalletTransaction;
import com.ewallet.common.TransactionStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferMetricsTest {
    @Test
    void registersTransferFailureCompensationAndSagaLatencyMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TransferMetrics metrics = new TransferMetrics(registry);
        WalletTransaction transaction = new WalletTransaction(
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
            false
        );

        metrics.recordFailed(transaction);
        metrics.recordCompensating();
        metrics.recordCompleted(transaction);

        assertThat(registry.find("transfer_failed_total").counter().count()).isEqualTo(1);
        assertThat(registry.find("transfer_compensating_total").counter().count()).isEqualTo(1);
        assertThat(registry.find("wallet_saga_latency").timer().count()).isEqualTo(2);
    }
}
