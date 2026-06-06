package com.ewallet.notification;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
class DlqMetrics {
    DlqMetrics(MeterRegistry registry, DlqReplayService replayService) {
        Gauge.builder("wallet_dlq_depth", replayService, DlqReplayService::depth)
            .description("Number of records currently present in wallet.events.v1.DLQ")
            .register(registry);
    }
}
