package com.ewallet.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@DirtiesContext
@SpringJUnitConfig
@EmbeddedKafka(partitions = 1, topics = {"wallet.events.v1", "wallet.events.v1.DLQ"})
class KafkaDlqIntegrationTest {
    @Autowired
    EmbeddedKafkaBroker embeddedKafka;

    @Test
    void poisonMessageGoesToDlqAndCanBeReplayed() throws Exception {
        KafkaTemplate<String, String> template = kafkaTemplate();
        DefaultKafkaConsumerFactory<String, String> listenerConsumerFactory = consumerFactory("poison-listener");
        ContainerProperties containerProperties = new ContainerProperties("wallet.events.v1");
        containerProperties.setMessageListener((MessageListener<String, String>) record -> {
            throw new IllegalArgumentException("poison");
        });
        KafkaMessageListenerContainer<String, String> container =
            new KafkaMessageListenerContainer<>(listenerConsumerFactory, containerProperties);
        container.setCommonErrorHandler(new KafkaDlqConfig().walletEventErrorHandler(template, "wallet.events.v1"));

        try {
            container.start();
            ContainerTestUtils.waitForAssignment(container, 1);

            template.send("wallet.events.v1", "account-1", "poison-value").get();

            ConsumerRecord<String, String> dlqRecord;
            try (Consumer<String, String> dlqConsumer = consumerFactory("dlq-reader").createConsumer()) {
                embeddedKafka.consumeFromAnEmbeddedTopic(dlqConsumer, "wallet.events.v1.DLQ");
                dlqRecord = KafkaTestUtils.getSingleRecord(dlqConsumer, "wallet.events.v1.DLQ", Duration.ofSeconds(10));
            }
            assertThat(dlqRecord.key()).isEqualTo("account-1");
            assertThat(dlqRecord.value()).isEqualTo("poison-value");

            DlqReplayService replayService = new DlqReplayService(consumerFactory("dlq-replayer"), template, "wallet.events.v1");
            assertThat(replayService.depth()).isGreaterThanOrEqualTo(1);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            new DlqMetrics(registry, replayService);
            assertThat(registry.find("wallet_dlq_depth").gauge().value()).isGreaterThanOrEqualTo(1);

            container.stop();

            DlqReplayResult result = replayService.replay(new DlqReplayRequest(dlqRecord.partition(), dlqRecord.offset()));
            assertThat(result.replayed()).isTrue();

            try (Consumer<String, String> sourceConsumer = consumerFactory("source-reader").createConsumer()) {
                embeddedKafka.consumeFromAnEmbeddedTopic(sourceConsumer, "wallet.events.v1");
                ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(sourceConsumer, Duration.ofSeconds(10));
                long matchingRecords = 0;
                for (ConsumerRecord<String, String> record : records.records("wallet.events.v1")) {
                    if ("poison-value".equals(record.value())) {
                        matchingRecords++;
                    }
                }
                assertThat(matchingRecords).isGreaterThanOrEqualTo(2);
            }
        } finally {
            container.stop();
        }
    }

    private KafkaTemplate<String, String> kafkaTemplate() {
        Map<String, Object> producerProps = new HashMap<>(KafkaTestUtils.producerProps(embeddedKafka));
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        KafkaTemplate<String, String> template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));
        template.setDefaultTopic("wallet.events.v1");
        return template;
    }

    private DefaultKafkaConsumerFactory<String, String> consumerFactory(String groupId) {
        Map<String, Object> consumerProps = new HashMap<>(KafkaTestUtils.consumerProps(groupId, "false", embeddedKafka));
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(consumerProps);
    }
}
