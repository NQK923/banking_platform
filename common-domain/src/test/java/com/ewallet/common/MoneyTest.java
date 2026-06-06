package com.ewallet.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    void storesMoneyAtCurrencyScale() {
        assertEquals("100", Money.of("100", "VND").asString());
        assertEquals("10.50", Money.of("10.50", "USD").asString());
    }

    @Test
    void rejectsUnsupportedScaleInsteadOfRounding() {
        assertThrows(ArithmeticException.class, () -> Money.of("10.25", "VND"));
    }
}
