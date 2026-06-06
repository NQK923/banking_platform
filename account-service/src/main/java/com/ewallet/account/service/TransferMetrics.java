package com.ewallet.account.service;

import com.ewallet.account.model.WalletTransaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
class TransferMetrics {
    private final Counter failed;
    private final Counter compensating;
    private final Timer sagaLatency;

    TransferMetrics(MeterRegistry registry) {
        this.failed = Counter.builder("transfer_failed_total")
            .description("Total failed wallet transfers")
            .register(registry);
        this.compensating = Counter.builder("transfer_compensating_total")
            .description("Total wallet transfers that entered compensation")
            .register(registry);
        this.sagaLatency = Timer.builder("wallet_saga_latency")
            .description("End-to-end wallet transfer saga latency")
            .register(registry);
    }

    void recordFailed(WalletTransaction transaction) {
        failed.increment();
        recordLatency(transaction);
    }

    void recordCompensating() {
        compensating.increment();
    }

    void recordCompleted(WalletTransaction transaction) {
        recordLatency(transaction);
    }

    private void recordLatency(WalletTransaction transaction) {
        sagaLatency.record(Duration.between(transaction.createdAt(), Instant.now()));
    }
}
