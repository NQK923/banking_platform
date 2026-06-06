package com.ewallet.notification;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class WalletEventNotificationListenerTest {
    @Test
    void acceptsWalletEventPayloadForMockNotification() {
        WalletEventNotificationListener listener = new WalletEventNotificationListener();
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
            "wallet.events.v1",
            0,
            0,
            "account-1",
            "{\"amount\":\"100\",\"currency\":\"VND\"}"
        );
        record.headers().add("event_type", "TransferCompleted".getBytes());

        assertThatCode(() -> listener.onWalletEvent(record)).doesNotThrowAnyException();
    }
}
