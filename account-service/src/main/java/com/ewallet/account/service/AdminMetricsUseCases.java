package com.ewallet.account.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class AdminMetricsUseCases {
    private final MeterRegistry registry;
    private final ReconciliationMetrics reconciliationMetrics;

    public AdminMetricsUseCases(MeterRegistry registry, ReconciliationMetrics reconciliationMetrics) {
        this.registry = registry;
        this.reconciliationMetrics = reconciliationMetrics;
    }

    public SystemMetrics metrics() {
        return new SystemMetrics(
            counter("transfer_failed_total"),
            counter("transfer_compensating_total"),
            timerMeanMillis("wallet_saga_latency"),
            gauge("wallet_consumer_lag"),
            gauge("wallet_dlq_depth"),
            reconciliationMetrics.driftCount()
        );
    }

    private double counter(String name) {
        var counter = registry.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    private double gauge(String name) {
        var gauge = registry.find(name).gauge();
        return gauge == null ? 0 : gauge.value();
    }

    private double timerMeanMillis(String name) {
        Timer timer = registry.find(name).timer();
        return timer == null ? 0 : timer.mean(TimeUnit.MILLISECONDS);
    }

    public record SystemMetrics(
        double transferFailedTotal,
        double transferCompensatingTotal,
        double walletSagaLatency,
        double walletConsumerLag,
        double walletDlqDepth,
        int reconciliationDrift
    ) {
    }
}
