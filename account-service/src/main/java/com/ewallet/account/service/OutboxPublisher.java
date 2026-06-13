package com.ewallet.account.service;

import com.ewallet.account.model.OutboxRecord;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "banking.outbox.publisher.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
    private final WalletStore store;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final FaultInjection faultInjection;

    public OutboxPublisher(
        WalletStore store,
        KafkaTemplate<String, String> kafkaTemplate,
        @Value("${banking.kafka.events-topic:wallet.events.v1}") String topic,
        FaultInjection faultInjection
    ) {
        this.store = store;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.faultInjection = faultInjection;
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
                record.headers().add("event_id", event.eventId().toString().getBytes());
                record.headers().add("event_type", event.eventType().getBytes());
                if (event.correlationId() != null) {
                    record.headers().add("correlation_id", event.correlationId().toString().getBytes());
                    record.headers().add("traceparent", Traceparent.fromCorrelationId(event.correlationId()).getBytes());
                }
                kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
                faultInjection.maybeFail(FaultInjection.AFTER_PUBLISH_BEFORE_MARK);
                store.markOutboxPublished(event.id());
            } catch (Exception ex) {
                store.markOutboxAttempt(event.id());
            }
        }
    }
}
