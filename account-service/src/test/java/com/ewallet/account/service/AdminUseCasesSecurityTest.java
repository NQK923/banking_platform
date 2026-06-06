package com.ewallet.account.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ewallet.common.DomainException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminUseCasesSecurityTest {
    @Test
    void adminReadRequiresNonNullActor() {
        AdminUseCases useCases = new AdminUseCases(org.mockito.Mockito.mock(WalletStore.class));

        assertThatThrownBy(() -> useCases.accounts(null))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("Admin actor is required");
    }

    @Test
    void adminReadAuditUsesAuthenticatedActorId() {
        WalletStore store = org.mockito.Mockito.mock(WalletStore.class);
        UUID actor = UUID.randomUUID();
        when(store.accounts()).thenReturn(List.of());

        new AdminUseCases(store).accounts(actor);

        verify(store).audit(
            eq("ADMIN"),
            eq(actor),
            eq("AdminAccountsViewed"),
            eq("ADMIN"),
            eq(actor),
            eq(Map.of()),
            isNull()
        );
    }
}
