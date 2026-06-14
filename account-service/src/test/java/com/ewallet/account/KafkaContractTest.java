package com.ewallet.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class KafkaContractTest {
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.7.1")
    );

    @Test
    void publishesWalletEventWithAggregateKeyAndHeaders() throws Exception {
        String topic = "wallet.events.v1";
        String key = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        String payload = "{\"amount\":\"100\",\"currency\":\"VND\"}";

        try (
            KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()
            ));
            KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "contract-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()
            ))
        ) {
            consumer.subscribe(List.of(topic));
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
            record.headers().add("event_type", "MoneyCredited".getBytes());
            record.headers().add("correlation_id", correlationId.getBytes());
            producer.send(record).get();
            producer.flush();

            var records = consumer.poll(Duration.ofSeconds(10));
            assertThat(records).hasSize(1);
            var consumed = records.iterator().next();
            assertThat(consumed.key()).isEqualTo(key);
            assertThat(consumed.value()).isEqualTo(payload);
            assertThat(new String(consumed.headers().lastHeader("event_type").value())).isEqualTo("MoneyCredited");
            assertThat(new String(consumed.headers().lastHeader("correlation_id").value())).isEqualTo(correlationId);
        }
    }
}
