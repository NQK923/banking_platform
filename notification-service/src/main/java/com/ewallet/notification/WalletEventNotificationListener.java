package com.ewallet.notification;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class WalletEventNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(WalletEventNotificationListener.class);
    private static final String CONSUMER_NAME = "notification-service";
    private final ProcessedEventStore processedEvents;
    private final NotificationSender notificationSender;

    public WalletEventNotificationListener(ProcessedEventStore processedEvents, NotificationSender notificationSender) {
        this.processedEvents = processedEvents;
        this.notificationSender = notificationSender;
    }

    @KafkaListener(topics = "${banking.kafka.events-topic:wallet.events.v1}", groupId = "${spring.kafka.consumer.group-id:notification-service}")
    public void onWalletEvent(ConsumerRecord<String, String> record) {
        String eventId = requiredHeader(record, "event_id");
        String eventType = optionalHeader(record, "event_type", "UnknownEvent");
        if (!processedEvents.markProcessed(CONSUMER_NAME, eventId)) {
            log.info("duplicate wallet event skipped eventId={} eventType={} aggregateId={}", eventId, eventType, record.key());
            return;
        }
        String traceparent = optionalHeader(record, "traceparent", null);
        notificationSender.send(eventId, eventType, record.key(), record.value(), traceparent);
    }

    private String requiredHeader(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value().length == 0) {
            throw new IllegalArgumentException("Missing required Kafka header: " + name);
        }
        return new String(header.value());
    }

    private String optionalHeader(ConsumerRecord<String, String> record, String name, String defaultValue) {
        Header header = record.headers().lastHeader(name);
        return header == null ? defaultValue : new String(header.value());
    }
}
