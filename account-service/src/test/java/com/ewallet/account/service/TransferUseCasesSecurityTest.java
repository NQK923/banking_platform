package com.ewallet.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ewallet.account.model.WalletTransaction;
import com.ewallet.account.model.AccountRecord;
import com.ewallet.common.AccountKind;
import com.ewallet.common.AccountStatus;
import com.ewallet.account.risk.RiskScoringService;
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
    void sameKeyRejectsChangedAmountRecipientOrNote() {
        WalletStore store = org.mockito.Mockito.mock(WalletStore.class);
        TransferUseCases useCases = useCases(store);
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        WalletTransaction original = new WalletTransaction(UUID.randomUUID(), sender, receiver, BigDecimal.TEN,
            "VND", TransactionStatus.COMPLETED, "key", UUID.randomUUID(), Instant.now(), Instant.now(), true, "note", null);
        when(store.findTransactionByIdempotency(sender, "key")).thenReturn(Optional.of(original));
        when(store.lookupAccount("receiver@example.test", null)).thenReturn(Optional.of(new AccountRecord(
            receiver, UUID.randomUUID(), null, "VND", AccountKind.USER, AccountStatus.ACTIVE, 0, Instant.now())));
        when(store.lookupAccount("other@example.test", null)).thenReturn(Optional.of(new AccountRecord(
            UUID.randomUUID(), UUID.randomUUID(), null, "VND", AccountKind.USER, AccountStatus.ACTIVE, 0, Instant.now())));
        AuthenticatedUser actor = new AuthenticatedUser(UUID.randomUUID(), sender, Set.of("ROLE_USER"));
        for (var request : new TransferUseCases.TransferRequest[] {
            new TransferUseCases.TransferRequest(null, "receiver@example.test", null, "11", "key", "123456", "note"),
            new TransferUseCases.TransferRequest(null, "other@example.test", null, "10", "key", "123456", "note"),
            new TransferUseCases.TransferRequest(null, "receiver@example.test", null, "10", "key", "123456", "changed")
        }) {
            assertThatThrownBy(() -> useCases.transfer(actor, request, null)).isInstanceOfSatisfying(DomainException.class,
                ex -> assertThat(ex.code()).isEqualTo("IDEMPOTENCY_PAYLOAD_MISMATCH"));
        }
        var same = new TransferUseCases.TransferRequest(null, "receiver@example.test", null, "10.0000", "key", "123456", "note");
        assertThat(useCases.transfer(actor, same, null)).isSameAs(original);
    }

    @Test
    void compensatingStatusDoesNotClaimACompletedRefund() {
        WalletTransaction tx = new WalletTransaction(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN,
            "VND", TransactionStatus.COMPENSATING, "key", UUID.randomUUID(), Instant.now(), Instant.now(), true, null, null);
        assertThat(tx.isCompensated()).isFalse();
        assertThat(tx.withStatus(TransactionStatus.FAILED).isCompensated()).isTrue();
    }

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
            "123456",
            null
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
            false,
            null,
            null
        );
        when(store.withLockedTransaction(eq(transactionId), org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            java.util.function.Function<WalletTransaction, WalletTransaction> action = invocation.getArgument(1);
            return action.apply(pending);
        });

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

    @Test
    void cannotCancelTerminalTransferEvenWhenDebitWasNotApplied() {
        WalletStore store = org.mockito.Mockito.mock(WalletStore.class);
        TransferUseCases useCases = useCases(store);
        UUID actorId = UUID.randomUUID();
        UUID senderAccountId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        WalletTransaction failed = new WalletTransaction(
            transactionId,
            senderAccountId,
            UUID.randomUUID(),
            BigDecimal.TEN,
            "VND",
            TransactionStatus.FAILED,
            "idem",
            UUID.randomUUID(),
            Instant.now(),
            Instant.now(),
            false,
            null,
            "INSUFFICIENT_FUNDS"
        );
        when(store.withLockedTransaction(eq(transactionId), org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            java.util.function.Function<WalletTransaction, WalletTransaction> action = invocation.getArgument(1);
            return action.apply(failed);
        });

        assertThatThrownBy(() -> useCases.cancel(
            transactionId,
            new AuthenticatedUser(actorId, senderAccountId, Set.of("ROLE_USER"))
        )).isInstanceOfSatisfying(
            DomainException.class,
            ex -> assertThat(ex.code()).isEqualTo("CANCEL_NOT_ALLOWED")
        );
    }

    private TransferUseCases useCases(WalletStore store) {
        return new TransferUseCases(
            store,
            org.mockito.Mockito.mock(AuthService.class),
            new TransferMetrics(new SimpleMeterRegistry()),
            new FaultInjection(""),
            new RiskScoringService(store),
            true,
            50
        );
    }
}
