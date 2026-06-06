package com.ewallet.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ewallet.common.TransactionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

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
