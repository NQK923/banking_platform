package com.ewallet.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.ewallet.account.security.TokenService;
import com.ewallet.common.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

class PinTransactionTest {
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Transactions {}

    @Test
    void failedWithdrawalTransactionCannotRollBackPinLockoutOrRestoreOldRefreshToken() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            CREATE TABLE users (
                id UUID PRIMARY KEY, email VARCHAR(255), phone VARCHAR(50), password_hash VARCHAR(255),
                pin_hash VARCHAR(255), refresh_token_hash VARCHAR(255), roles VARCHAR(255), status VARCHAR(50),
                created_at TIMESTAMP, failed_pin_attempts INT, pin_locked_until TIMESTAMP
            )
            """);
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users VALUES (?, 'user@example.test', NULL, 'password', 'pin', 'old', 'ROLE_USER', 'ACTIVE', ?, 0, NULL)",
            userId, Timestamp.from(Instant.now()));
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        WalletStore store = new WalletStore(jdbc, encoder, new ObjectMapper(), mock(BalanceCache.class),
            mock(FaultInjection.class), "unused", "123456");
        var stale = store.findUser(userId).orElseThrow();
        assertThat(store.rotateRefreshTokenHash(userId, "old", "rotated")).isTrue();
        store.saveUserPinState(stale);
        assertThat(jdbc.queryForObject("SELECT refresh_token_hash FROM users WHERE id = ?", String.class, userId)).isEqualTo("rotated");

        var manager = new DataSourceTransactionManager(dataSource);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Transactions.class);
            context.registerBean("transactionManager", PlatformTransactionManager.class, () -> manager);
            context.registerBean(AuthService.class, () -> new AuthService(store, mock(TokenService.class), encoder,
                mock(PasswordResetEmailSender.class), 10));
            context.refresh();
            AuthService auth = context.getBean(AuthService.class);
            var withdrawal = new TransactionTemplate(manager);
            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> withdrawal.execute(status -> {
                    auth.verifyPin(userId, "wrong");
                    return null;
                })).isInstanceOfSatisfying(DomainException.class,
                    ex -> assertThat(ex.code()).isEqualTo("PIN_INVALID"));
            }
            assertThat(jdbc.queryForObject("SELECT failed_pin_attempts FROM users WHERE id = ?", Integer.class, userId)).isEqualTo(5);
            assertThatThrownBy(() -> auth.verifyPin(userId, "123456")).isInstanceOfSatisfying(DomainException.class,
                ex -> assertThat(ex.code()).isEqualTo("PIN_LOCKED"));
            assertThat(jdbc.queryForObject("SELECT refresh_token_hash FROM users WHERE id = ?", String.class, userId)).isEqualTo("rotated");
        } finally {
            jdbc.execute("DROP ALL OBJECTS");
        }
    }
}
