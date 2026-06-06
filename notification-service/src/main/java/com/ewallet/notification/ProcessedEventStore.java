package com.ewallet.notification;

public interface ProcessedEventStore {
    boolean markProcessed(String consumerName, String eventId);
}
