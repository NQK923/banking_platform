package com.ewallet.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class LoggingNotificationSender implements NotificationSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(String eventId, String eventType, String aggregateId, String payload) {
        log.info("mock-notification eventId={} eventType={} aggregateId={} payload={}", eventId, eventType, aggregateId, payload);
    }
}
