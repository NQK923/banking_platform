package com.ewallet.account.service;

import com.ewallet.common.DomainException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FaultInjection {
    public static final String CREDIT_STEP = "credit-step";
    public static final String AFTER_STATE_BEFORE_OUTBOX = "after-state-before-outbox";
    public static final String BEFORE_OUTBOX = "before-outbox";
    public static final String AFTER_OUTBOX = "after-outbox";
    public static final String AFTER_PUBLISH_BEFORE_MARK = "after-publish-before-mark";

    private final Set<String> enabled = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> remainingFailures = new ConcurrentHashMap<>();

    public FaultInjection(@Value("${banking.fault-injection.points:}") String configuredPoints) {
        if (configuredPoints != null && !configuredPoints.isBlank()) {
            Arrays.stream(configuredPoints.split(","))
                .map(String::trim)
                .filter(point -> !point.isBlank())
                .forEach(enabled::add);
        }
    }

    public void enable(String point) {
        enabled.add(point);
    }

    public void enableOnce(String point) {
        remainingFailures.put(point, new AtomicInteger(1));
    }

    public void clear() {
        enabled.clear();
        remainingFailures.clear();
    }

    void maybeFail(String point) {
        AtomicInteger remaining = remainingFailures.get(point);
        if (remaining != null && remaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            throw new DomainException("FAULT_INJECTED", "Fault injected at " + point);
        }
        if (enabled.contains(point)) {
            throw new DomainException("FAULT_INJECTED", "Fault injected at " + point);
        }
    }
}
