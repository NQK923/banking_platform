package com.ewallet.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class WalletEventNotificationListenerTest {
    @Test
    void skipsDuplicateEventIdForMockNotification() {
        RecordingSender sender = new RecordingSender();
        WalletEventNotificationListener listener = new WalletEventNotificationListener(new InMemoryProcessedEventStore(), sender);
        String eventId = "event-1";
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "wallet.events.v1",
            0,
            0,
            "account-1",
            "{\"amount\":\"100\",\"currency\":\"VND\"}"
        );
        record.headers().add("event_id", eventId.getBytes());
        record.headers().add("event_type", "TransferCompleted".getBytes());

        listener.onWalletEvent(record);
        listener.onWalletEvent(record);

        assertThat(sender.sentCount()).isEqualTo(1);
    }

    private static final class InMemoryProcessedEventStore implements ProcessedEventStore {
        private final Set<String> processed = new HashSet<>();

        @Override
        public boolean markProcessed(String consumerName, String eventId) {
            return processed.add(consumerName + ":" + eventId);
        }
    }

    private static final class RecordingSender implements NotificationSender {
        private int sentCount;

        @Override
        public void send(String eventId, String eventType, String aggregateId, String payload) {
            sentCount++;
        }

        int sentCount() {
            return sentCount;
        }
    }
}
