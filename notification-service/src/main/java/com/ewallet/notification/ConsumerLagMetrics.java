package com.ewallet.notification;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Component;

@Component
class ConsumerLagMetrics {
    private final ConsumerFactory<String, String> consumerFactory;
    private final String topic;
    private final String groupId;

    ConsumerLagMetrics(
        MeterRegistry registry,
        ConsumerFactory<String, String> consumerFactory,
        @Value("${banking.kafka.events-topic:wallet.events.v1}") String topic,
        @Value("${spring.kafka.consumer.group-id:notification-service}") String groupId
    ) {
        this.consumerFactory = consumerFactory;
        this.topic = topic;
        this.groupId = groupId;
        Gauge.builder("wallet_consumer_lag", this, ConsumerLagMetrics::lag)
            .description("Kafka consumer lag for wallet event notification consumer")
            .register(registry);
    }

    long lag() {
        try (Consumer<String, String> consumer = consumerFactory.createConsumer(groupId, "lag-reader")) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic, Duration.ofSeconds(2));
            if (infos == null || infos.isEmpty()) {
                return 0;
            }
            List<TopicPartition> partitions = infos.stream()
                .map(info -> new TopicPartition(topic, info.partition()))
                .toList();
            var endOffsets = consumer.endOffsets(partitions);
            long lag = 0;
            for (TopicPartition partition : partitions) {
                OffsetAndMetadata committed = consumer.committed(partition, Duration.ofSeconds(2));
                long committedOffset = committed == null ? 0 : committed.offset();
                lag += Math.max(0, endOffsets.getOrDefault(partition, 0L) - committedOffset);
            }
            return lag;
        }
    }
}
