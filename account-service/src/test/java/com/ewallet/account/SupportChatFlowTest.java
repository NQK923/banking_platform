package com.ewallet.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ewallet.account.service.FaultInjection;
import com.ewallet.common.TransactionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
class SupportChatFlowTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("banking_platform_support_test")
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
    FaultInjection faultInjection;

    @AfterEach
    void clearFaultInjection() {
        faultInjection.clear();
    }

    @Test
    void compensatedTransferRefundAnswerIsGroundedByBackendData() throws Exception {
        Auth sender = register("support-refund-sender@example.test", "+84910000001");
        Auth receiver = register("support-refund-receiver@example.test", "+84910000002");
        String admin = login("admin@local.test", "Admin123!").accessToken();
        postJson("/api/accounts/" + sender.accountId() + "/deposit", admin, "{\"amount\":\"500\"}", null)
            .andExpect(status().isOk());

        faultInjection.enable(FaultInjection.CREDIT_STEP);
        JsonNode transfer = postJson(
            "/api/transactions/transfer",
            sender.accessToken(),
            "{\"recipientEmail\":\"support-refund-receiver@example.test\",\"amount\":\"300\",\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"pin\":\"123456\"}",
            UUID.randomUUID().toString()
        ).andExpect(status().isOk()).andReturnJson();
        JsonNode failed = waitForTerminalStatus(transfer.get("id").asText(), sender.accessToken());
        assertThat(failed.get("status").asText()).isEqualTo(TransactionStatus.FAILED.name());
        assertThat(failed.get("compensated").asBoolean()).isTrue();
        Integer ledgerEntriesBeforeSupport = jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class);

        JsonNode support = postJson(
            "/api/support/chat/sessions",
            sender.accessToken(),
            "{\"initialMessage\":\"Has my money been refunded?\",\"context\":{\"transactionId\":\"" + transfer.get("id").asText() + "\"}}",
            null
        ).andExpect(status().isOk()).andReturnJson();

        assertThat(support.get("answer").asText()).contains("compensated = true").contains("reversed back");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM support_ai_tool_calls", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class)).isEqualTo(ledgerEntriesBeforeSupport);
        assertThat(receiver.accountId()).isNotBlank();
    }

    @Test
    void assistantDoesNotConfirmRefundWithoutCompensatedFlag() throws Exception {
        Auth sender = register("support-failed-sender@example.test", "+84910000011");
        Auth receiver = register("support-failed-receiver@example.test", "+84910000012");
        JsonNode transfer = postJson(
            "/api/transactions/transfer",
            sender.accessToken(),
            "{\"recipientEmail\":\"support-failed-receiver@example.test\",\"amount\":\"999\",\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"pin\":\"123456\"}",
            UUID.randomUUID().toString()
        ).andExpect(status().isOk()).andReturnJson();
        JsonNode failed = waitForTerminalStatus(transfer.get("id").asText(), sender.accessToken());
        assertThat(failed.get("status").asText()).isEqualTo(TransactionStatus.FAILED.name());
        assertThat(failed.get("compensated").asBoolean()).isFalse();

        JsonNode support = postJson(
            "/api/support/chat/sessions",
            sender.accessToken(),
            "{\"initialMessage\":\"Was I refunded?\",\"context\":{\"transactionId\":\"" + transfer.get("id").asText() + "\"}}",
            null
        ).andExpect(status().isOk()).andReturnJson();

        assertThat(support.get("answer").asText()).doesNotContain("reversed back to your wallet");
        assertThat(support.get("answer").asText()).contains("No refund is required");
        assertThat(receiver.accountId()).isNotBlank();
    }

    @Test
    void assistantAnswersVietnameseSupportQuestionsInVietnamese() throws Exception {
        Auth user = register("support-vietnamese-user@example.test", "+84910000013");

        JsonNode support = postJson(
            "/api/support/chat/sessions",
            user.accessToken(),
            "{\"initialMessage\":\"Tại sao số dư của tôi chưa cập nhật?\"}",
            null
        ).andExpect(status().isOk()).andReturnJson();

        assertThat(support.get("answer").asText())
            .contains("Số dư hiển thị")
            .contains("backend");
    }

    @Test
    void secretMessagesAreRedactedAndHumanHandoffCanBeClosedByAdmin() throws Exception {
        Auth user = register("support-secret-user@example.test", "+84910000021");
        String admin = login("admin@local.test", "Admin123!").accessToken();
        JsonNode support = postJson(
            "/api/support/chat/sessions",
            user.accessToken(),
            "{\"initialMessage\":\"my pin 123456 is not working\"}",
            null
        ).andExpect(status().isOk()).andReturnJson();

        String sessionId = support.get("sessionId").asText();
        JsonNode session = getJson("/api/support/chat/sessions/" + sessionId, user.accessToken());
        assertThat(session.toString()).contains("pin [REDACTED]").doesNotContain("123456");

        JsonNode handoff = postJson(
            "/api/support/chat/sessions/" + sessionId + "/handoff",
            user.accessToken(),
            "{\"reason\":\"USER_REQUESTED_HUMAN\"}",
            null
        ).andExpect(status().isOk()).andReturnJson();
        String caseId = handoff.get("caseId").asText();

        JsonNode cases = getJson("/api/admin/support/cases?status=OPEN&page=0&size=10", admin);
        assertThat(cases.get("totalElements").asInt()).isEqualTo(1);
        assertThat(cases.get("items").get(0).get("caseId").asText()).isEqualTo(caseId);

        postJson(
            "/api/admin/support/cases/" + caseId + "/reply",
            admin,
            "{\"message\":\"We can help you reset your PIN from security settings.\"}",
            null
        ).andExpect(status().isOk());

        JsonNode closed = postJson(
            "/api/admin/support/cases/" + caseId + "/close",
            admin,
            "{\"resolution\":\"PIN safety explained.\"}",
            null
        ).andExpect(status().isOk()).andReturnJson();
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(getJson("/api/admin/support/cases/" + caseId, admin).get("messages").toString())
            .contains("We can help you reset your PIN");
    }

    @Test
    void userCannotAskAboutAnotherUsersTransaction() throws Exception {
        Auth sender = register("support-owner-sender@example.test", "+84910000031");
        Auth receiver = register("support-owner-receiver@example.test", "+84910000032");
        Auth stranger = register("support-owner-stranger@example.test", "+84910000033");
        String admin = login("admin@local.test", "Admin123!").accessToken();
        postJson("/api/accounts/" + sender.accountId() + "/deposit", admin, "{\"amount\":\"200\"}", null)
            .andExpect(status().isOk());
        JsonNode transfer = postJson(
            "/api/transactions/transfer",
            sender.accessToken(),
            "{\"recipientEmail\":\"support-owner-receiver@example.test\",\"amount\":\"50\",\"idempotencyKey\":\"" + UUID.randomUUID() + "\",\"pin\":\"123456\"}",
            UUID.randomUUID().toString()
        ).andExpect(status().isOk()).andReturnJson();

        postJson(
            "/api/support/chat/sessions",
            stranger.accessToken(),
            "{\"initialMessage\":\"What happened to this transfer?\",\"context\":{\"transactionId\":\"" + transfer.get("id").asText() + "\"}}",
            null
        ).andExpect(status().isForbidden());
        assertThat(receiver.accountId()).isNotBlank();
    }

    @Test
    void missingTransactionContextReturnsSafeFallbackInsteadOfGuessing() throws Exception {
        Auth user = register("support-missing-tx@example.test", "+84910000041");
        UUID missingTransactionId = UUID.randomUUID();

        JsonNode support = postJson(
            "/api/support/chat/sessions",
            user.accessToken(),
            "{\"initialMessage\":\"Has this transaction been refunded?\",\"context\":{\"transactionId\":\"" + missingTransactionId + "\"}}",
            null
        ).andExpect(status().isOk()).andReturnJson();

        assertThat(support.get("answer").asText())
            .contains("I cannot confirm the status")
            .contains("contact human support");
        assertThat(jdbc.queryForObject(
            "SELECT success FROM support_ai_tool_calls WHERE session_id = ? AND tool_name = 'getTransaction'",
            Boolean.class,
            UUID.fromString(support.get("sessionId").asText())
        )).isFalse();
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
