package com.ewallet.account.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class BalanceCache {
    private final StringRedisTemplate redis;

    public BalanceCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<BigDecimal> get(UUID accountId) {
        try {
            String value = redis.opsForValue().get(key(accountId));
            return value == null ? Optional.empty() : Optional.of(new BigDecimal(value));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    public void put(UUID accountId, BigDecimal balance) {
        try {
            redis.opsForValue().set(key(accountId), balance.toPlainString());
        } catch (RuntimeException ex) {
            // Redis is display-only; persistence remains PostgreSQL.
        }
    }

    private String key(UUID accountId) {
        return "account:balance:" + accountId;
    }
}
