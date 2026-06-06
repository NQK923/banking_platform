package com.ewallet.account.service;

import com.ewallet.account.model.ReconciliationReport;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationMetrics {
    private final AtomicInteger driftCount = new AtomicInteger();

    public ReconciliationMetrics(MeterRegistry registry) {
        Gauge.builder("reconciliation_drift", driftCount, AtomicInteger::get)
            .description("Number of reconciliation drift findings from the last run")
            .register(registry);
    }

    void record(ReconciliationReport report) {
        driftCount.set(report.driftCount());
    }

    public int driftCount() {
        return driftCount.get();
    }
}
