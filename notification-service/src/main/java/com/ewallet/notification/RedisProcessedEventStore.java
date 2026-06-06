package com.ewallet.notification;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class RedisProcessedEventStore implements ProcessedEventStore {
    private final StringRedisTemplate redis;

    RedisProcessedEventStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean markProcessed(String consumerName, String eventId) {
        Boolean stored = redis.opsForValue().setIfAbsent("processed-event:" + consumerName + ":" + eventId, "1");
        return Boolean.TRUE.equals(stored);
    }
}
