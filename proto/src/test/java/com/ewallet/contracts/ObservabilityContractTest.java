package com.ewallet.contracts;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ObservabilityContractTest {
    @Test
    void servicesIncludeOpenTelemetryAndPrometheusScrapeEndpoint() throws Exception {
        for (String module : new String[] {
            "account-service",
            "api-gateway",
            "audit-service",
            "notification-service",
            "reconciliation-service",
            "transaction-service"
        }) {
            String buildFile = Files.readString(Path.of("../" + module + "/build.gradle.kts"));
            String applicationYaml = Files.readString(Path.of("../" + module + "/src/main/resources/application.yml"));
            assertTrue(buildFile.contains("micrometer-tracing-bridge-otel"), "Missing OTel tracing bridge in " + module);
            assertTrue(buildFile.contains("opentelemetry-exporter-otlp"), "Missing OTLP exporter in " + module);
            assertTrue(applicationYaml.contains("prometheus"), "Missing Prometheus actuator exposure in " + module);
        }
    }

    @Test
    void observabilityArtifactsContainAllWalletProductionMeters() throws Exception {
        String alerts = Files.readString(Path.of("../observability/prometheus-alerts.yml"));
        String dashboard = Files.readString(Path.of("../observability/grafana-wallet-dashboard.json"));
        for (String metric : new String[] {
            "transfer_failed_total",
            "transfer_compensating_total",
            "wallet_saga_latency",
            "wallet_consumer_lag",
            "wallet_dlq_depth",
            "reconciliation_drift"
        }) {
            assertTrue(alerts.contains(metric), "Missing alert metric: " + metric);
            assertTrue(dashboard.contains(metric), "Missing dashboard metric: " + metric);
        }
    }
}
