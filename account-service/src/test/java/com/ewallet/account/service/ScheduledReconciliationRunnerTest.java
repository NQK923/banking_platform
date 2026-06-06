package com.ewallet.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class ScheduledReconciliationRunnerTest {
    @Test
    void scheduledRunnerReusesReconciliationUseCases() throws Exception {
        Method run = ScheduledReconciliationRunner.class.getDeclaredMethod("run");
        Scheduled scheduled = run.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${banking.reconciliation.fixed-delay-ms:300000}");

        ReconciliationUseCases useCases = mock(ReconciliationUseCases.class);
        new ScheduledReconciliationRunner(useCases).run();

        verify(useCases).run();
    }
}
