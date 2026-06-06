package com.ewallet.notification;

public record DlqReplayResult(boolean replayed, String message) {
}
