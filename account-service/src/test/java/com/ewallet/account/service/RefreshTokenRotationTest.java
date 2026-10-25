package com.ewallet.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ewallet.account.model.UserRecord;
import com.ewallet.account.security.TokenService;
import com.ewallet.common.AccountStatus;
import com.ewallet.common.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;

class RefreshTokenRotationTest {
    @Test
    void concurrentRotationAllowsExactlyOneWinnerInTheDatabase() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE users (id UUID PRIMARY KEY, refresh_token_hash VARCHAR(255), failed_pin_attempts INT)");
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users VALUES (?, 'old', 4)", userId);
        WalletStore store = new WalletStore(jdbc, mock(PasswordEncoder.class), new ObjectMapper(),
            mock(BalanceCache.class), mock(FaultInjection.class), "unused", "123456");
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> { start.await(); return store.rotateRefreshTokenHash(userId, "old", "new-a"); });
            var second = executor.submit(() -> { start.await(); return store.rotateRefreshTokenHash(userId, "old", "new-b"); });
            start.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS) ^ second.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT failed_pin_attempts FROM users WHERE id = ?", Integer.class, userId)).isEqualTo(4);
            assertThat(store.rotateRefreshTokenHash(userId, "old", "replayed")).isFalse();
        } finally {
            executor.shutdownNow();
            jdbc.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void refreshRejectsLostRotationAndDoesNotCreateAnAdminWallet() {
        WalletStore store = mock(WalletStore.class);
        TokenService tokens = new TokenService(new ObjectMapper(), "test-secret-test-secret-test-secret");
        UUID userId = UUID.randomUUID();
        String oldToken = "old-refresh";
        String hash = tokens.hashRefreshToken(oldToken);
        UserRecord admin = new UserRecord(userId, "admin@example.test", null, "password", "pin", hash,
            Set.of("ROLE_ADMIN"), AccountStatus.ACTIVE, Instant.now(), 4, null);
        when(store.findUserByRefreshTokenHash(hash)).thenReturn(Optional.of(admin));
        when(store.optionalUserAccount(userId, "VND")).thenReturn(Optional.empty());
        when(store.rotateRefreshTokenHash(eq(userId), eq(hash), anyString())).thenReturn(true, false);
        AuthService auth = new AuthService(store, tokens, mock(PasswordEncoder.class), mock(PasswordResetEmailSender.class), 10);

        var refreshed = auth.refresh(new AuthService.RefreshRequest(oldToken));
        assertThat(refreshed.accountId()).isNull();
        assertThat(refreshed.refreshToken()).isNotEqualTo(oldToken);
        assertThat(tokens.parseAccessToken(refreshed.accessToken()).roles()).containsExactly("ROLE_ADMIN");
        assertThatThrownBy(() -> auth.refresh(new AuthService.RefreshRequest(oldToken)))
            .isInstanceOfSatisfying(DomainException.class, ex -> assertThat(ex.code()).isEqualTo("AUTH_INVALID"));
        verify(store, never()).userAccount(any(), anyString());
        verify(store, never()).saveUserPinState(any());
    }
}
