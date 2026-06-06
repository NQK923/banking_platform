package com.ewallet.notification;

public record DlqReplayRequest(int partition, long offset) {
}
