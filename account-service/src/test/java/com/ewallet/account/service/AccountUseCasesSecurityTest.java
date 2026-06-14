package com.ewallet.account.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ewallet.common.DomainException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountUseCasesSecurityTest {
    @Test
    void mockDepositRequiresUserSession() {
        AccountUseCases useCases = new AccountUseCases(
            org.mockito.Mockito.mock(WalletStore.class),
            org.mockito.Mockito.mock(AuthService.class)
        );

        assertThatThrownBy(() -> useCases.deposit(UUID.randomUUID(), new AccountUseCases.MoneyRequest("100", null), null))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("User session is required");
    }

    @Test
    void mockWithdrawRequiresUserSession() {
        AccountUseCases useCases = new AccountUseCases(
            org.mockito.Mockito.mock(WalletStore.class),
            org.mockito.Mockito.mock(AuthService.class)
        );

        assertThatThrownBy(() -> useCases.withdraw(UUID.randomUUID(), new AccountUseCases.MoneyRequest("100", null), null))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("User session is required");
    }
}
