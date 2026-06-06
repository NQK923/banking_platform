package com.ewallet.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

public record Money(BigDecimal amount, String currency) {
    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        currency = currency.toUpperCase();
        amount = normalize(amount, currency);
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public Money add(Money other) {
        assertSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        assertSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public String asString() {
        return amount.toPlainString();
    }

    public void assertSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new DomainException("CURRENCY_MISMATCH", "Currencies do not match");
        }
    }

    private static BigDecimal normalize(BigDecimal value, String currency) {
        int scale = CurrencyScale.scaleFor(currency);
        return value.setScale(scale, RoundingMode.UNNECESSARY);
    }
}
