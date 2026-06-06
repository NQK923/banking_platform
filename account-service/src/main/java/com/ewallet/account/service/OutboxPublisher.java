package com.ewallet.account.service;

import com.ewallet.account.model.OutboxRecord;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher {
    private final WalletStore store;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;

    public OutboxPublisher(
        WalletStore store,
        KafkaTemplate<String, String> kafkaTemplate,
        @Value("${banking.kafka.events-topic:wallet.events.v1}") String topic
    ) {
        this.store = store;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Scheduled(fixedDelayString = "${banking.outbox.fixed-delay-ms:1000}")
    void publishBatch() {
        for (OutboxRecord event : store.unpublishedOutbox(50)) {
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(
                    topic,
                    event.aggregateId().toString(),
                    event.payload()
                );
                record.headers().add("event_type", event.eventType().getBytes());
                if (event.correlationId() != null) {
                    record.headers().add("correlation_id", event.correlationId().toString().getBytes());
                }
                kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
                store.markOutboxPublished(event.id());
            } catch (Exception ex) {
                store.markOutboxAttempt(event.id());
            }
        }
    }
}
