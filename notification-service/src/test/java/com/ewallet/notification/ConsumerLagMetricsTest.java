package com.ewallet.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;

class ConsumerLagMetricsTest {
    @Test
    void registersConsumerLagGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        @SuppressWarnings("unchecked")
        ConsumerFactory<String, String> consumerFactory = org.mockito.Mockito.mock(ConsumerFactory.class);
        @SuppressWarnings("unchecked")
        Consumer<String, String> consumer = org.mockito.Mockito.mock(Consumer.class);
        when(consumerFactory.createConsumer(eq("notification-service"), eq("lag-reader"), eq(""), any(Properties.class))).thenReturn(consumer);
        when(consumer.partitionsFor(eq("wallet.events.v1"), any(Duration.class))).thenReturn(List.of());

        new ConsumerLagMetrics(registry, consumerFactory, "wallet.events.v1", "notification-service");

        assertThat(registry.find("wallet_consumer_lag").gauge().value()).isZero();
        verify(consumer).close();
    }
}
