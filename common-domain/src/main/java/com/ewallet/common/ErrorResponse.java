package com.ewallet.common;

public record ErrorResponse(String code, String message, String traceId) {
}
