package com.ewallet.notification;

public interface NotificationSender {
    void send(String eventId, String eventType, String aggregateId, String payload);
}
