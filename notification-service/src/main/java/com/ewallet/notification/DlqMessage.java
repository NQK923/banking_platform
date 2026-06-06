package com.ewallet.notification;

public record DlqMessage(
    int partition,
    long offset,
    String key,
    String value,
    String eventId,
    String eventType
) {
}
