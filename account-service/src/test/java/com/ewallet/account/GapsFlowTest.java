package com.ewallet.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ewallet.common.TransactionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
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

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class GapsFlowTest {
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
        registry.add("banking.grpc.port", () -> "0");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.ewallet.account.service.FaultInjection faultInjection;

    @org.junit.jupiter.api.AfterEach
    void cleanFaults() {
        faultInjection.clear();
    }

    @Test
    void testChangePinAndRateLimitingLockout() throws Exception {
        Auth user = register("pinuser@example.test", "+84000000901", "123456");

        // Change PIN to invalid format (not 6 digits) -> should be 400 Bad Request
        postJson("/api/accounts/pin/change", user.accessToken(), "{\"currentPin\":\"123456\",\"newPin\":\"12345\"}", null)
            .andExpect(status().isBadRequest());

        // Failed attempts: try changing PIN with wrong current PIN 4 times
        for (int i = 0; i < 4; i++) {
            postJson("/api/accounts/pin/change", user.accessToken(), "{\"currentPin\":\"999999\",\"newPin\":\"654321\"}", null)
                .andExpect(status().isForbidden());
        }

        // 5th attempt -> will fail and trigger lock
        postJson("/api/accounts/pin/change", user.accessToken(), "{\"currentPin\":\"999999\",\"newPin\":\"654321\"}", null)
            .andExpect(status().isForbidden());

        // Subsequent attempt -> should return PIN_LOCKED (403)
        JsonNode response = postJson("/api/accounts/pin/change", user.accessToken(), "{\"currentPin\":\"123456\",\"newPin\":\"654321\"}", null)
            .andExpect(status().isForbidden()).andReturnJson();
        assertThat(response.get("code").asText()).isEqualTo("PIN_LOCKED");

        // Register another user for happy path pin change testing
        Auth user2 = register("pinuser2@example.test", "+84000000902", "111111");
        
        // Change PIN successfully
        postJson("/api/accounts/pin/change", user2.accessToken(), "{\"currentPin\":\"111111\",\"newPin\":\"222222\"}", null)
            .andExpect(status().isOk());

        // Verify that transfer with old PIN fails
        Auth receiver = register("receiver@example.test", "+84000000903", "123456");
        String admin = login("admin@local.test", "Admin123!").accessToken();
        postJson("/api/accounts/" + user2.accountId() + "/deposit", admin, "{\"amount\":\"500\"}", null)
            .andExpect(status().isOk());

        postJson(
            "/api/transactions/transfer",
            user2.accessToken(),
            "{\"recipientEmail\":\"receiver@example.test\",\"amount\":\"100\",\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"pin\":\"111111\"}",
            null
        ).andExpect(status().isForbidden());

        // Verify that transfer with new PIN succeeds
        postJson(
            "/api/transactions/transfer",
            user2.accessToken(),
            "{\"recipientEmail\":\"receiver@example.test\",\"amount\":\"100\",\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"pin\":\"222222\"}",
            null
        ).andExpect(status().isOk());
    }

    @Test
    void testWithdrawPinVerification() throws Exception {
        Auth user = register("withdrawuser@example.test", "+84000000904", "123456");
        String admin = login("admin@local.test", "Admin123!").accessToken();
        
        postJson("/api/accounts/" + user.accountId() + "/deposit", admin, "{\"amount\":\"500\"}", null)
            .andExpect(status().isOk());

        // Withdraw with wrong PIN -> should be 403 Forbidden
        postJson("/api/accounts/" + user.accountId() + "/withdraw", user.accessToken(), "{\"amount\":\"100\",\"pin\":\"999999\"}", null)
            .andExpect(status().isForbidden());

        // Withdraw without PIN -> should be 403 Forbidden
        postJson("/api/accounts/" + user.accountId() + "/withdraw", user.accessToken(), "{\"amount\":\"100\"}", null)
            .andExpect(status().isForbidden());

        // Withdraw with correct PIN -> should succeed
        postJson("/api/accounts/" + user.accountId() + "/withdraw", user.accessToken(), "{\"amount\":\"100\",\"pin\":\"123456\"}", null)
            .andExpect(status().isOk());

        JsonNode balance = getJson("/api/accounts/" + user.accountId() + "/balance", user.accessToken());
        assertThat(balance.get("balance").asText()).isEqualTo("400");
    }

    @Test
    void testTransactionNoteAndFailureReason() throws Exception {
        Auth sender = register("notesender@example.test", "+84000000905", "123456");
        Auth receiver = register("notereceiver@example.test", "+84000000906", "123456");
        String admin = login("admin@local.test", "Admin123!").accessToken();

        postJson("/api/accounts/" + sender.accountId() + "/deposit", admin, "{\"amount\":\"500\"}", null)
            .andExpect(status().isOk());

        // Transfer with a note
        String idempotencyKey = UUID.randomUUID().toString();
        JsonNode transfer = postJson(
            "/api/transactions/transfer",
            sender.accessToken(),
            "{\"recipientEmail\":\"notereceiver@example.test\",\"amount\":\"100\",\"idempotencyKey\":\"" 
            + idempotencyKey + "\",\"pin\":\"123456\",\"note\":\"Water bill payment\"}",
            null
        ).andExpect(status().isOk()).andReturnJson();

        String txId = transfer.get("id").asText();
        JsonNode txDetail = getJson("/api/transactions/" + txId, sender.accessToken());
        assertThat(txDetail.get("note").asText()).isEqualTo("Water bill payment");

        // Forced failure transfer to check failureReason
        JsonNode failedTransfer = postJson(
            "/api/transactions/transfer",
            sender.accessToken(),
            "{\"recipientEmail\":\"notereceiver@example.test\",\"amount\":\"99999\",\"idempotencyKey\":\"" 
            + UUID.randomUUID() + "\",\"pin\":\"123456\",\"note\":\"Tra tien nuoc\"}",
            null
        ).andExpect(status().isOk()).andReturnJson();

        String failedTxId = failedTransfer.get("id").asText();
        JsonNode failedTxDetail = waitForTerminalStatus(failedTxId, sender.accessToken());
        assertThat(failedTxDetail.get("status").asText()).isEqualTo("FAILED");
        assertThat(failedTxDetail.get("failureReason").asText()).contains("INSUFFICIENT_FUNDS");
    }

    @Test
    void testTransactionCompensationAndRefundFlag() throws Exception {
        Auth sender = register("compensate-sender@example.test", "+84000000910", "123456");
        Auth receiver = register("compensate-receiver@example.test", "+84000000911", "123456");
        String admin = login("admin@local.test", "Admin123!").accessToken();

        postJson("/api/accounts/" + sender.accountId() + "/deposit", admin, "{\"amount\":\"500\"}", null)
            .andExpect(status().isOk());

        // Enable credit step failure to trigger Saga compensation
        faultInjection.enable(com.ewallet.account.service.FaultInjection.CREDIT_STEP);

        // Initiate transfer
        String idempotencyKey = UUID.randomUUID().toString();
        JsonNode transfer = postJson(
            "/api/transactions/transfer",
            sender.accessToken(),
            "{\"recipientEmail\":\"compensate-receiver@example.test\",\"amount\":\"100\",\"idempotencyKey\":\"" 
            + idempotencyKey + "\",\"pin\":\"123456\",\"note\":\"Compensation test\"}",
            null
        ).andExpect(status().isOk()).andReturnJson();

        String txId = transfer.get("id").asText();
        JsonNode txDetail = waitForTerminalStatus(txId, sender.accessToken());

        assertThat(txDetail.get("status").asText()).isEqualTo("FAILED");
        assertThat(txDetail.get("debitApplied").asBoolean()).isTrue();
        assertThat(txDetail.get("compensated").asBoolean()).isTrue();
        assertThat(txDetail.get("failureReason").asText()).contains("Credit failed, transaction compensated");

        // Verify balance was refunded (still 500 on sender, 0 on receiver)
        assertThat(getJson("/api/accounts/" + sender.accountId() + "/balance", sender.accessToken()).get("balance").asText()).isEqualTo("500");
        assertThat(getJson("/api/accounts/" + receiver.accountId() + "/balance", receiver.accessToken()).get("balance").asText()).isEqualTo("0");

        // Verify that history items also return the compensated flag
        JsonNode rawHistory = getJson("/api/accounts/" + sender.accountId() + "/history", sender.accessToken());
        assertThat(rawHistory.isArray()).isTrue();
        JsonNode histTx = rawHistory.get(0);
        assertThat(histTx.get("compensated").asBoolean()).isTrue();
    }

    @Test
    void testPaginatedTransactionHistory() throws Exception {
        Auth sender = register("pagesender@example.test", "+84000000907", "123456");
        Auth receiver = register("pagereceiver@example.test", "+84000000908", "123456");
        String admin = login("admin@local.test", "Admin123!").accessToken();

        postJson("/api/accounts/" + sender.accountId() + "/deposit", admin, "{\"amount\":\"5000\"}", null)
            .andExpect(status().isOk());

        // Create 15 transfers to have 15 history items
        for (int i = 0; i < 15; i++) {
            postJson(
                "/api/transactions/transfer",
                sender.accessToken(),
                "{\"recipientEmail\":\"pagereceiver@example.test\",\"amount\":\"10\",\"idempotencyKey\":\"" 
                + UUID.randomUUID() + "\",\"pin\":\"123456\",\"note\":\"Tx " + i + "\"}",
                null
            ).andExpect(status().isOk());
        }

        // Test unpaginated history (returns List<WalletTransaction> raw array)
        JsonNode rawHistory = getJson("/api/accounts/" + sender.accountId() + "/history", sender.accessToken());
        assertThat(rawHistory.isArray()).isTrue();
        assertThat(rawHistory).hasSize(15);

        // Test paginated history: page=0, size=10
        JsonNode page0 = getJson("/api/accounts/" + sender.accountId() + "/history?page=0&size=10", sender.accessToken());
        assertThat(page0.isObject()).isTrue();
        assertThat(page0.get("items")).hasSize(10);
        assertThat(page0.get("page").asInt()).isEqualTo(0);
        assertThat(page0.get("size").asInt()).isEqualTo(10);
        assertThat(page0.get("totalElements").asLong()).isEqualTo(15);
        assertThat(page0.get("totalPages").asInt()).isEqualTo(2);

        // Test paginated history: page=1, size=10
        JsonNode page1 = getJson("/api/accounts/" + sender.accountId() + "/history?page=1&size=10", sender.accessToken());
        assertThat(page1.get("items")).hasSize(5);

        // Test paginated history: page=2, size=10 (out-of-range)
        JsonNode page2 = getJson("/api/accounts/" + sender.accountId() + "/history?page=2&size=10", sender.accessToken());
        assertThat(page2.get("items")).isEmpty();
        assertThat(page2.get("totalElements").asLong()).isEqualTo(15);
        assertThat(page2.get("totalPages").asInt()).isEqualTo(2);
    }

    private Auth register(String email, String phone, String pin) throws Exception {
        JsonNode json = postJson(
            "/api/auth/register",
            null,
            "{\"email\":\"" + email + "\",\"phone\":\"" + phone + "\",\"password\":\"Password123!\",\"pin\":\"" + pin + "\",\"currency\":\"VND\"}",
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
