package com.ewallet.account.service;

import com.ewallet.common.DomainException;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FaultInjection {
    public static final String CREDIT_STEP = "credit-step";
    public static final String BEFORE_OUTBOX = "before-outbox";
    public static final String AFTER_OUTBOX = "after-outbox";
    public static final String AFTER_PUBLISH_BEFORE_MARK = "after-publish-before-mark";

    private final Set<String> enabled = ConcurrentHashMap.newKeySet();

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

    public void clear() {
        enabled.clear();
    }

    void maybeFail(String point) {
        if (enabled.contains(point)) {
            throw new DomainException("FAULT_INJECTED", "Fault injected at " + point);
        }
    }
}
