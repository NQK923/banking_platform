package com.ewallet.notification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class WalletEventNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(WalletEventNotificationListener.class);

    @KafkaListener(topics = "${banking.kafka.events-topic:wallet.events.v1}", groupId = "${spring.kafka.consumer.group-id:notification-service}")
    public void onWalletEvent(ConsumerRecord<String, String> record) {
        String eventType = record.headers().lastHeader("event_type") == null
            ? "UnknownEvent"
            : new String(record.headers().lastHeader("event_type").value());
        log.info("mock-notification eventType={} aggregateId={} payload={}", eventType, record.key(), record.value());
    }
}
