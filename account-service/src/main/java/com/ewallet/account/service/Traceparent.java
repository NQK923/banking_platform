package com.ewallet.account.service;

import java.util.UUID;

final class Traceparent {
    private Traceparent() {
    }

    static String fromCorrelationId(UUID correlationId) {
        String traceId = correlationId.toString().replace("-", "");
        String spanId = traceId.substring(0, 16);
        return "00-" + traceId + "-" + spanId + "-01";
    }
}
