package com.ewallet.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ewallet.account.model.OutboxRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

class OutboxPublisherTest {
    @Test
    void publishesTraceparentWithOutboxEvent() {
        WalletStore store = org.mockito.Mockito.mock(WalletStore.class);
        KafkaTemplate<String, String> kafkaTemplate = org.mockito.Mockito.mock(KafkaTemplate.class);
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        FaultInjection faultInjection = new FaultInjection("");
        when(store.unpublishedOutbox(50)).thenReturn(List.of(new OutboxRecord(
            UUID.randomUUID(),
            eventId,
            aggregateId,
            "TransferCompleted",
            "{\"eventId\":\"" + eventId + "\"}",
            correlationId,
            0,
            Instant.now()
        )));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        new OutboxPublisher(store, kafkaTemplate, "wallet.events.v1", faultInjection).publishBatch();

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.key()).isEqualTo(aggregateId.toString());
        assertThat(new String(record.headers().lastHeader("event_id").value())).isEqualTo(eventId.toString());
        assertThat(new String(record.headers().lastHeader("correlation_id").value())).isEqualTo(correlationId.toString());
        assertThat(new String(record.headers().lastHeader("traceparent").value()))
            .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
    }

    @Test
    void publishFailureBeforeMarkLeavesOutboxForRetry() {
        WalletStore store = org.mockito.Mockito.mock(WalletStore.class);
        KafkaTemplate<String, String> kafkaTemplate = org.mockito.Mockito.mock(KafkaTemplate.class);
        FaultInjection faultInjection = new FaultInjection("");
        OutboxRecord event = new OutboxRecord(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "TransferCompleted",
            "{}",
            UUID.randomUUID(),
            0,
            Instant.now()
        );
        when(store.unpublishedOutbox(50)).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        faultInjection.enable(FaultInjection.AFTER_PUBLISH_BEFORE_MARK);
        new OutboxPublisher(store, kafkaTemplate, "wallet.events.v1", faultInjection).publishBatch();

        verify(store, never()).markOutboxPublished(event.id());
        verify(store).markOutboxAttempt(event.id());

        faultInjection.clear();
        new OutboxPublisher(store, kafkaTemplate, "wallet.events.v1", faultInjection).publishBatch();

        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
        verify(store).markOutboxPublished(event.id());
    }
}
