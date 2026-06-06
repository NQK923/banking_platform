package com.ewallet.account.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ewallet.common.DomainException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountUseCasesSecurityTest {
    @Test
    void mockDepositRequiresAdminActor() {
        AccountUseCases useCases = new AccountUseCases(org.mockito.Mockito.mock(WalletStore.class));

        assertThatThrownBy(() -> useCases.deposit(UUID.randomUUID(), new AccountUseCases.MoneyRequest("100"), null))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("Admin actor is required");
    }

    @Test
    void mockWithdrawRequiresAdminActor() {
        AccountUseCases useCases = new AccountUseCases(org.mockito.Mockito.mock(WalletStore.class));

        assertThatThrownBy(() -> useCases.withdraw(UUID.randomUUID(), new AccountUseCases.MoneyRequest("100"), null))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("Admin actor is required");
    }
}
