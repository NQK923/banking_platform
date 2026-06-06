package com.ewallet.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ewallet.common.TransactionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.AfterEach;
import com.ewallet.account.service.FaultInjection;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AccountServiceFlowTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("banking_platform_test")
        .withUsername("banking")
        .withPassword("banking");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("banking.jwt-secret", () -> "test-secret-test-secret-test-secret");
        registry.add("banking.seed-admin-password", () -> "Admin123!");
        registry.add("banking.seed-admin-pin", () -> "000000");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    FaultInjection faultInjection;

    @AfterEach
    void clearFaultInjection() {
        faultInjection.clear();
    }

    @Test
    void userCanDepositTransferAndReconcile() throws Exception {
        Auth alice = register("alice@example.test", "+84000000001");
        Auth bob = register("bob@example.test", "+84000000002");
        String admin = login("admin@local.test", "Admin123!").accessToken();

        postJson("/api/accounts/" + alice.accountId() + "/deposit", admin, "{\"amount\":\"1000\"}", null)
            .andExpect(status().isOk());

        JsonNode balance = getJson("/api/accounts/" + alice.accountId() + "/balance", alice.accessToken());
        assertThat(balance.get("balance").asText()).isEqualTo("1000");

        JsonNode transfer = postJson(
            "/api/transactions/transfer",
            alice.accessToken(),
            "{\"recipientEmail\":\"bob@example.test\",\"amount\":\"250\",\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"pin\":\"123456\"}",
            UUID.randomUUID().toString()
        ).andExpect(status().isOk()).andReturnJson();

        JsonNode completed = waitForStatus(transfer.get("id").asText(), alice.accessToken(), TransactionStatus.COMPLETED);
        assertThat(completed.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(getJson("/api/accounts/" + bob.accountId() + "/balance", bob.accessToken()).get("balance").asText()).isEqualTo("250");

        JsonNode report = postJson("/api/reconciliation/run", admin, "{}", null)
            .andExpect(status().isOk()).andReturnJson();
        assertThat(report.get("zeroDrift").asBoolean()).isTrue();
    }

    @Test
    void sadPathsAreAutomated() throws Exception {
        Auth sender = register("sad-sender@example.test", "+84000000101");
        Auth receiver = register("sad-receiver@example.test", "+84000000102");
        String admin = login("admin@local.test", "Admin123!").accessToken();

        JsonNode insufficient = postJson(
            "/api/transactions/transfer",
            sender.accessToken(),
            "{\"recipientEmail\":\"sad-receiver@example.test\",\"amount\":\"999\",\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"pin\":\"123456\"}",
            UUID.randomUUID().toString()
        ).andExpect(status().isOk()).andReturnJson();
        assertThat(waitForStatus(insufficient.get("id").asText(), sender.accessToken(), TransactionStatus.FAILED).get("status").asText()).isEqualTo("FAILED");

        postJson("/api/accounts/" + sender.accountId() + "/deposit", admin, "{\"amount\":\"500\"}", null)
            .andExpect(status().isOk());

        JsonNode transfer = postJson(
            "/api/transactions/transfer",
            sender.accessToken(),
            "{\"recipientEmail\":\"sad-receiver@example.test\",\"amount\":\"300\",\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"pin\":\"123456\"}",
            UUID.randomUUID().toString()
        ).andExpect(status().isOk()).andReturnJson();
        postJson("/api/admin/accounts/" + receiver.accountId() + "/suspend", admin, "{}", null)
            .andExpect(status().isOk());

        assertThat(waitForStatus(transfer.get("id").asText(), sender.accessToken(), TransactionStatus.FAILED).get("status").asText()).isEqualTo("FAILED");
        assertThat(getJson("/api/accounts/" + sender.accountId() + "/balance", sender.accessToken()).get("balance").asText()).isEqualTo("500");

        JsonNode audit = getJson("/api/admin/audit", admin);
        assertThat(audit.toString()).contains("MoneyDebitReversed").contains("AccountSuspended");
    }

    @Test
    void duplicateTransferCommandDoesNotDoubleLedger() throws Exception {
        Auth sender = register("duplicate-sender@example.test", "+84000000201");
        Auth receiver = register("duplicate-receiver@example.test", "+84000000202");
        String admin = login("admin@local.test", "Admin123!").accessToken();
        postJson("/api/accounts/" + sender.accountId() + "/deposit", admin, "{\"amount\":\"500\"}", null)
            .andExpect(status().isOk());

        String idempotencyKey = UUID.randomUUID().toString();
        String body = "{\"recipientEmail\":\"duplicate-receiver@example.test\",\"amount\":\"200\",\"idempotencyKey\":\""
            + idempotencyKey + "\",\"pin\":\"123456\"}";
        JsonNode first = postJson("/api/transactions/transfer", sender.accessToken(), body, idempotencyKey)
            .andExpect(status().isOk()).andReturnJson();
        JsonNode duplicate = postJson("/api/transactions/transfer", sender.accessToken(), body, idempotencyKey)
            .andExpect(status().isOk()).andReturnJson();

        assertThat(duplicate.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(waitForStatus(first.get("id").asText(), sender.accessToken(), TransactionStatus.COMPLETED).get("status").asText())
            .isEqualTo("COMPLETED");

        JsonNode senderLedger = getJson("/api/accounts/" + sender.accountId() + "/ledger", sender.accessToken());
        JsonNode receiverLedger = getJson("/api/accounts/" + receiver.accountId() + "/ledger", receiver.accessToken());
        assertThat(senderLedger).hasSize(2);
        assertThat(receiverLedger).hasSize(1);
        assertThat(getJson("/api/accounts/" + sender.accountId() + "/balance", sender.accessToken()).get("balance").asText()).isEqualTo("300");
        assertThat(getJson("/api/accounts/" + receiver.accountId() + "/balance", receiver.accessToken()).get("balance").asText()).isEqualTo("200");
    }

    @Test
    void parallelTransfersFromOneAccountCannotOverdrawOrCreateMoney() throws Exception {
        Auth sender = register("parallel-sender@example.test", "+84000000301");
        Auth receiver = register("parallel-receiver@example.test", "+84000000302");
        String admin = login("admin@local.test", "Admin123!").accessToken();
        postJson("/api/accounts/" + sender.accountId() + "/deposit", admin, "{\"amount\":\"500\"}", null)
            .andExpect(status().isOk());

        int requestCount = 10;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        List<Future<JsonNode>> futures = new ArrayList<>();
        for (int i = 0; i < requestCount; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                String idempotencyKey = UUID.randomUUID().toString();
                String body = "{\"recipientEmail\":\"parallel-receiver@example.test\",\"amount\":\"100\",\"idempotencyKey\":\""
                    + idempotencyKey + "\",\"pin\":\"123456\"}";
                return postJson("/api/transactions/transfer", sender.accessToken(), body, idempotencyKey)
                    .andExpect(status().isOk()).andReturnJson();
            }));
        }
        start.countDown();

        List<String> transactionIds = new ArrayList<>();
        for (Future<JsonNode> future : futures) {
            transactionIds.add(future.get(10, TimeUnit.SECONDS).get("id").asText());
        }
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        int completed = 0;
        int failed = 0;
        for (String transactionId : transactionIds) {
            String status = waitForTerminalStatus(transactionId, sender.accessToken()).get("status").asText();
            if (TransactionStatus.COMPLETED.name().equals(status)) {
                completed++;
            } else if (TransactionStatus.FAILED.name().equals(status)) {
                failed++;
            }
        }

        assertThat(completed).isEqualTo(5);
        assertThat(failed).isEqualTo(5);
        assertThat(getJson("/api/accounts/" + sender.accountId() + "/balance", sender.accessToken()).get("balance").asText()).isEqualTo("0");
        assertThat(getJson("/api/accounts/" + receiver.accountId() + "/balance", receiver.accessToken()).get("balance").asText()).isEqualTo("500");
        BigDecimal storedSenderBalance = jdbc.queryForObject(
            "SELECT balance FROM account_balances WHERE account_id = ?",
            BigDecimal.class,
            UUID.fromString(sender.accountId())
        );
        assertThat(storedSenderBalance).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        JsonNode report = postJson("/api/reconciliation/run", admin, "{}", null)
            .andExpect(status().isOk()).andReturnJson();
        assertThat(report.get("zeroDrift").asBoolean()).isTrue();
        assertThat(report.get("totalByCurrency").get("VND").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void reconciliationDriftIsPersistedMeteredAndFreezesAffectedAccount() throws Exception {
        Auth user = register("drift-user@example.test", "+84000000401");
        String admin = login("admin@local.test", "Admin123!").accessToken();
        postJson("/api/accounts/" + user.accountId() + "/deposit", admin, "{\"amount\":\"100\"}", null)
            .andExpect(status().isOk());
        jdbc.update("UPDATE account_balances SET balance = 99 WHERE account_id = ?", UUID.fromString(user.accountId()));

        JsonNode report = postJson("/api/reconciliation/run", admin, "{}", null)
            .andExpect(status().isOk()).andReturnJson();

        assertThat(report.get("zeroDrift").asBoolean()).isFalse();
        assertThat(report.get("driftCount").asInt()).isGreaterThan(0);
        assertThat(report.get("findings").toString()).contains("balance drift for account " + user.accountId());
        assertThat(meterRegistry.find("reconciliation_drift").gauge().value()).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_findings", Integer.class)).isGreaterThan(0);
        assertThat(getJson("/api/accounts/" + user.accountId(), user.accessToken()).get("status").asText()).isEqualTo("SUSPENDED");
    }

    @Test
    void injectedCreditStepFailureCompensatesAndKeepsLedgerBalanced() throws Exception {
        Auth sender = register("chaos-sender@example.test", "+84000000501");
        Auth receiver = register("chaos-receiver@example.test", "+84000000502");
        String admin = login("admin@local.test", "Admin123!").accessToken();
        postJson("/api/accounts/" + sender.accountId() + "/deposit", admin, "{\"amount\":\"500\"}", null)
            .andExpect(status().isOk());

        faultInjection.enable(FaultInjection.CREDIT_STEP);
        JsonNode transfer = postJson(
            "/api/transactions/transfer",
            sender.accessToken(),
            "{\"recipientEmail\":\"chaos-receiver@example.test\",\"amount\":\"300\",\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"pin\":\"123456\"}",
            UUID.randomUUID().toString()
        ).andExpect(status().isOk()).andReturnJson();

        assertThat(waitForTerminalStatus(transfer.get("id").asText(), sender.accessToken()).get("status").asText()).isEqualTo("FAILED");
        assertThat(getJson("/api/accounts/" + sender.accountId() + "/balance", sender.accessToken()).get("balance").asText()).isEqualTo("500");
        assertThat(getJson("/api/accounts/" + receiver.accountId() + "/balance", receiver.accessToken()).get("balance").asText()).isEqualTo("0");
        JsonNode report = postJson("/api/reconciliation/run", admin, "{}", null)
            .andExpect(status().isOk()).andReturnJson();
        assertThat(report.get("zeroDrift").asBoolean()).isTrue();
    }

    @Test
    void injectedBeforeOutboxFailureRollsBackLedgerMutation() throws Exception {
        Auth user = register("outbox-chaos@example.test", "+84000000503");
        String admin = login("admin@local.test", "Admin123!").accessToken();

        faultInjection.enable(FaultInjection.BEFORE_OUTBOX);
        postJson("/api/accounts/" + user.accountId() + "/deposit", admin, "{\"amount\":\"100\"}", null)
            .andExpect(status().isBadRequest());

        faultInjection.clear();
        assertThat(getJson("/api/accounts/" + user.accountId() + "/balance", user.accessToken()).get("balance").asText()).isEqualTo("0");
    }

    @Test
    void wipedBalanceProjectionRebuildsExactlyFromSnapshotsAndEvents() throws Exception {
        Auth user = register("rebuild-user@example.test", "+84000000601");
        String admin = login("admin@local.test", "Admin123!").accessToken();
        postJson("/api/accounts/" + user.accountId() + "/deposit", admin, "{\"amount\":\"100\"}", null)
            .andExpect(status().isOk());
        postJson("/api/admin/snapshots/write", admin, "{}", null)
            .andExpect(status().isOk());
        postJson("/api/accounts/" + user.accountId() + "/deposit", admin, "{\"amount\":\"23\"}", null)
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
            """
                SELECT count(*)
                FROM account_events e
                JOIN (
                    SELECT account_id, max(version) AS snapshot_version
                    FROM account_snapshots
                    GROUP BY account_id
                ) s ON s.account_id = e.account_id
                WHERE e.account_id = ? AND e.version > s.snapshot_version
                """,
            Integer.class,
            UUID.fromString(user.accountId())
        )).isGreaterThan(0);

        jdbc.update("DELETE FROM account_balances");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_balances", Integer.class)).isZero();

        postJson("/api/admin/projections/rebuild-balances", admin, "{}", null)
            .andExpect(status().isOk());

        assertThat(getJson("/api/accounts/" + user.accountId() + "/balance", user.accessToken()).get("balance").asText()).isEqualTo("123");
        assertThat(jdbc.queryForObject(
            "SELECT balance FROM account_balances WHERE account_id = ?",
            BigDecimal.class,
            UUID.fromString(user.accountId())
        )).isEqualByComparingTo(new BigDecimal("123.0000"));
        JsonNode report = postJson("/api/reconciliation/run", admin, "{}", null)
            .andExpect(status().isOk()).andReturnJson();
        assertThat(report.get("zeroDrift").asBoolean()).isTrue();
    }

    private Auth register(String email, String phone) throws Exception {
        JsonNode json = postJson(
            "/api/auth/register",
            null,
            "{\"email\":\"" + email + "\",\"phone\":\"" + phone + "\",\"password\":\"Password123!\",\"pin\":\"123456\",\"currency\":\"VND\"}",
            null
        ).andExpect(status().isOk()).andReturnJson();
        return new Auth(json.get("accessToken").asText(), json.get("refreshToken").asText(), json.get("userId").asText(), json.get("accountId").asText());
    }

    private Auth login(String identifier, String password) throws Exception {
        JsonNode json = postJson(
            "/api/auth/login",
            null,
            "{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}",
            null
        ).andExpect(status().isOk()).andReturnJson();
        return new Auth(json.get("accessToken").asText(), json.get("refreshToken").asText(), json.get("userId").asText(), json.get("accountId").asText());
    }

    private JsonNode waitForStatus(String transactionId, String token, TransactionStatus expected) throws Exception {
        JsonNode tx = null;
        for (int i = 0; i < 20; i++) {
            tx = getJson("/api/transactions/" + transactionId, token);
            if (expected.name().equals(tx.get("status").asText())) {
                return tx;
            }
            Thread.sleep(100);
        }
        return tx;
    }

    private JsonNode waitForTerminalStatus(String transactionId, String token) throws Exception {
        JsonNode tx = null;
        for (int i = 0; i < 60; i++) {
            tx = getJson("/api/transactions/" + transactionId, token);
            String status = tx.get("status").asText();
            if (TransactionStatus.COMPLETED.name().equals(status) || TransactionStatus.FAILED.name().equals(status)) {
                return tx;
            }
            Thread.sleep(100);
        }
        return tx;
    }

    private JsonNode getJson(String path, String token) throws Exception {
        var builder = get(path).accept(MediaType.APPLICATION_JSON);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return objectMapper.readTree(mvc.perform(builder).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private Result postJson(String path, String token, String body, String idempotencyKey) throws Exception {
        var builder = post(path).contentType(MediaType.APPLICATION_JSON).content(body).accept(MediaType.APPLICATION_JSON);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return new Result(mvc.perform(builder), objectMapper);
    }

    private record Auth(String accessToken, String refreshToken, String userId, String accountId) {
    }

    private record Result(org.springframework.test.web.servlet.ResultActions actions, ObjectMapper objectMapper) {
        Result andExpect(org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            actions.andExpect(matcher);
            return this;
        }

        JsonNode andReturnJson() throws Exception {
            return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
        }
    }
}
