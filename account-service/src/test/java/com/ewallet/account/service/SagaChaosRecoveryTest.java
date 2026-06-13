package com.ewallet.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ewallet.common.TransactionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SagaChaosRecoveryTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("banking_platform_saga_chaos_test")
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
        registry.add("banking.grpc.port", () -> "0");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    WalletStore store;

    @Autowired
    FaultInjection faultInjection;

    @AfterEach
    void clearFaultInjection() {
        faultInjection.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        FaultInjection.AFTER_STATE_BEFORE_OUTBOX,
        FaultInjection.BEFORE_OUTBOX,
        FaultInjection.AFTER_OUTBOX
    })
    void crashedStateOutboxBoundaryCanBeReplayedIntoCompensatedSadPathWithoutLedgerDrift(String faultPoint) throws Exception {
        Auth sender = register("chaos-" + faultPoint + "-sender@example.test", "+84910" + Math.abs(faultPoint.hashCode()));
        Auth receiver = register("chaos-" + faultPoint + "-receiver@example.test", "+84911" + Math.abs(faultPoint.hashCode()));
        String admin = login("admin@local.test", "Admin123!").accessToken();
        postJson("/api/accounts/" + sender.accountId() + "/deposit", admin, "{\"amount\":\"500\"}", null)
            .andExpect(status().isOk());

        String idempotencyKey = UUID.randomUUID().toString();
        String body = "{\"recipientEmail\":\"" + "chaos-" + faultPoint + "-receiver@example.test"
            + "\",\"amount\":\"300\",\"idempotencyKey\":\"" + idempotencyKey + "\",\"pin\":\"123456\"}";

        faultInjection.enableOnce(faultPoint);
        postJson("/api/transactions/transfer", sender.accessToken(), body, idempotencyKey)
            .andExpect(status().isBadRequest());
        assertLedgerInvariants();

        faultInjection.enable(FaultInjection.CREDIT_STEP);
        JsonNode replayed = postJson("/api/transactions/transfer", sender.accessToken(), body, idempotencyKey)
            .andExpect(status().isOk())
            .andReturnJson();
        assertThat(waitForTerminalStatus(replayed.get("id").asText(), sender.accessToken()).get("status").asText())
            .isEqualTo(TransactionStatus.FAILED.name());
        faultInjection.clear();

        assertThat(getJson("/api/accounts/" + sender.accountId() + "/balance", sender.accessToken()).get("balance").asText()).isEqualTo("500");
        assertThat(getJson("/api/accounts/" + receiver.accountId() + "/balance", receiver.accessToken()).get("balance").asText()).isEqualTo("0");
        assertLedgerInvariants();
        assertThat(postJson("/api/reconciliation/run", admin, "{}", null).andExpect(status().isOk()).andReturnJson().get("zeroDrift").asBoolean())
            .isTrue();

        Integer ledgerRowsBeforeDuplicate = jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class);
        JsonNode duplicate = postJson("/api/transactions/transfer", sender.accessToken(), body, idempotencyKey)
            .andExpect(status().isOk())
            .andReturnJson();

        assertThat(duplicate.get("id").asText()).isEqualTo(replayed.get("id").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class)).isEqualTo(ledgerRowsBeforeDuplicate);
        assertLedgerInvariants();
    }

    @Test
    void crashAfterPublishBeforeMarkLeavesOutboxForIdempotentReplayWithoutLedgerDrift() throws Exception {
        Auth sender = register("publish-crash-sender@example.test", "+849120000001");
        Auth receiver = register("publish-crash-receiver@example.test", "+849120000002");
        String admin = login("admin@local.test", "Admin123!").accessToken();
        postJson("/api/accounts/" + sender.accountId() + "/deposit", admin, "{\"amount\":\"500\"}", null)
            .andExpect(status().isOk());

        faultInjection.enable(FaultInjection.CREDIT_STEP);
        JsonNode transfer = postJson(
            "/api/transactions/transfer",
            sender.accessToken(),
            "{\"recipientEmail\":\"publish-crash-receiver@example.test\",\"amount\":\"300\",\"idempotencyKey\":\""
                + UUID.randomUUID() + "\",\"pin\":\"123456\"}",
            UUID.randomUUID().toString()
        ).andExpect(status().isOk()).andReturnJson();
        assertThat(waitForTerminalStatus(transfer.get("id").asText(), sender.accessToken()).get("status").asText())
            .isEqualTo(TransactionStatus.FAILED.name());
        faultInjection.clear();
        assertLedgerInvariants();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_outbox WHERE published = FALSE", Integer.class)).isGreaterThan(0);

        KafkaTemplate<String, String> kafkaTemplate = org.mockito.Mockito.mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        faultInjection.enableOnce(FaultInjection.AFTER_PUBLISH_BEFORE_MARK);
        new OutboxPublisher(store, kafkaTemplate, "wallet.events.v1", faultInjection).publishBatch();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_outbox WHERE published = FALSE AND attempts > 0", Integer.class))
            .isGreaterThan(0);
        assertLedgerInvariants();

        new OutboxPublisher(store, kafkaTemplate, "wallet.events.v1", faultInjection).publishBatch();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_outbox WHERE published = FALSE", Integer.class)).isZero();
        assertThat(getJson("/api/accounts/" + sender.accountId() + "/balance", sender.accessToken()).get("balance").asText()).isEqualTo("500");
        assertThat(getJson("/api/accounts/" + receiver.accountId() + "/balance", receiver.accessToken()).get("balance").asText()).isEqualTo("0");
        assertThat(postJson("/api/reconciliation/run", admin, "{}", null).andExpect(status().isOk()).andReturnJson().get("zeroDrift").asBoolean())
            .isTrue();
        assertLedgerInvariants();
    }

    private void assertLedgerInvariants() {
        assertThat(jdbc.queryForObject(
            """
                SELECT count(*)
                FROM (
                    SELECT journal_id, currency, SUM(amount) AS total
                    FROM ledger_entries
                    GROUP BY journal_id, currency
                    HAVING SUM(amount) <> 0
                ) unbalanced
                """,
            Integer.class
        )).isZero();
        assertThat(jdbc.queryForObject(
            """
                SELECT count(*)
                FROM (
                    SELECT currency, SUM(amount) AS total
                    FROM ledger_entries
                    GROUP BY currency
                    HAVING SUM(amount) <> 0
                ) drift
                """,
            Integer.class
        )).isZero();
        assertThat(jdbc.queryForObject(
            """
                SELECT count(*)
                FROM account_balances b
                LEFT JOIN (
                    SELECT account_id, SUM(amount) AS balance
                    FROM ledger_entries
                    GROUP BY account_id
                ) l ON l.account_id = b.account_id
                WHERE COALESCE(l.balance, 0) <> b.balance
                """,
            Integer.class
        )).isZero();
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
