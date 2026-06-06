package com.ewallet.contracts;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SecurityConfigContractTest {
    @Test
    void runtimeConfigExternalizesSecrets() throws Exception {
        for (Path path : new Path[] {
            Path.of("../docker-compose.yml"),
            Path.of("../account-service/src/main/resources/application.yml"),
            Path.of("../transaction-service/src/main/resources/application.yml"),
            Path.of("../audit-service/src/main/resources/application.yml"),
            Path.of("../reconciliation-service/src/main/resources/application.yml"),
            Path.of("../notification-service/src/main/resources/application.yml"),
            Path.of("../api-gateway/src/main/resources/application.yml")
        }) {
            String content = Files.readString(path);
            assertTrue(!content.contains("POSTGRES_PASSWORD: banking"), "Hardcoded postgres password in " + path);
            assertTrue(!content.contains("jwt-secret: test"), "Hardcoded JWT secret in " + path);
            assertTrue(!content.contains("BANKING_JWT_SECRET=local"), "Hardcoded JWT env in " + path);
            assertTrue(!content.contains("INTERNAL_SERVICE_TOKEN=local"), "Hardcoded internal token in " + path);
            assertTrue(!content.contains("BANKING_SEED_ADMIN_PASSWORD=Admin123!"), "Hardcoded seed admin password in " + path);
        }
    }
}
