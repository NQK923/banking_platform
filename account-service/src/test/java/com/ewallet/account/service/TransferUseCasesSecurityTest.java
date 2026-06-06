package com.ewallet.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ewallet.account.model.WalletTransaction;
import com.ewallet.account.security.AuthenticatedUser;
import com.ewallet.common.DomainException;
import com.ewallet.common.TransactionStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferUseCasesSecurityTest {
    @Test
    void userCannotTransferFromAnotherAccountAtServiceLayer() {
        TransferUseCases useCases = useCases(org.mockito.Mockito.mock(WalletStore.class));
        AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), Set.of("ROLE_USER"));
        TransferUseCases.TransferRequest request = new TransferUseCases.TransferRequest(
            UUID.randomUUID().toString(),
            "receiver@example.test",
            null,
            "100",
            UUID.randomUUID().toString(),
            "123456"
        );

        assertThatThrownBy(() -> useCases.transfer(user, request, null))
            .isInstanceOfSatisfying(DomainException.class, ex -> assertThat(ex.code()).isEqualTo("FORBIDDEN"));
    }

    @Test
    void cancelWritesNonNullUserAuditActor() {
        WalletStore store = org.mockito.Mockito.mock(WalletStore.class);
        TransferUseCases useCases = useCases(store);
        UUID actorId = UUID.randomUUID();
        UUID senderAccountId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        WalletTransaction pending = new WalletTransaction(
            transactionId,
            senderAccountId,
            UUID.randomUUID(),
            BigDecimal.TEN,
            "VND",
            TransactionStatus.PENDING,
            "idem",
            correlationId,
            Instant.now(),
            Instant.now(),
            false
        );
        when(store.findTransaction(transactionId)).thenReturn(Optional.of(pending));

        WalletTransaction cancelled = useCases.cancel(
            transactionId,
            new AuthenticatedUser(actorId, senderAccountId, Set.of("ROLE_USER"))
        );

        assertThat(cancelled.status()).isEqualTo(TransactionStatus.CANCELLED);
        verify(store).audit(
            eq("TRANSACTION"),
            eq(transactionId),
            eq("TransferCancelled"),
            eq("USER"),
            eq(actorId),
            eq(Map.of("status", "CANCELLED")),
            eq(correlationId)
        );
    }

    private TransferUseCases useCases(WalletStore store) {
        return new TransferUseCases(
            store,
            org.mockito.Mockito.mock(AuthService.class),
            new TransferMetrics(new SimpleMeterRegistry()),
            new FaultInjection("")
        );
    }
}
