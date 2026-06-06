package com.ewallet.common;

import java.util.Map;

public final class CurrencyScale {
    private static final Map<String, Integer> SCALES = Map.of(
        "VND", 0,
        "USD", 2,
        "BTC", 8
    );

    private CurrencyScale() {
    }

    public static int scaleFor(String currency) {
        Integer scale = SCALES.get(currency.toUpperCase());
        if (scale == null) {
            throw new DomainException("UNSUPPORTED_CURRENCY", "Unsupported currency: " + currency);
        }
        return scale;
    }
}
