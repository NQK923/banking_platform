package com.ewallet.account.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class BalanceCache {
    private final StringRedisTemplate redis;
    private final boolean redisEnabled;
    private final Map<UUID, BigDecimal> inMemoryCache = new ConcurrentHashMap<>();

    public BalanceCache(StringRedisTemplate redis, @Value("${banking.balance-cache.redis.enabled:true}") boolean redisEnabled) {
        this.redis = redis;
        this.redisEnabled = redisEnabled;
    }

    public Optional<BigDecimal> get(UUID accountId) {
        if (!redisEnabled) {
            return Optional.ofNullable(inMemoryCache.get(accountId));
        }
        try {
            String value = redis.opsForValue().get(key(accountId));
            return value == null ? Optional.empty() : Optional.of(new BigDecimal(value));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    public void put(UUID accountId, BigDecimal balance) {
        if (!redisEnabled) {
            inMemoryCache.put(accountId, balance);
            return;
        }
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
