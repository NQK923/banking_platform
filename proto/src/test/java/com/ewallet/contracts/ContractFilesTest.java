package com.ewallet.contracts;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContractFilesTest {
    @Test
    void openApiContainsFrontendConsumedEndpointsAndErrorShape() throws Exception {
        String openapi = Files.readString(Path.of("openapi.yaml"));
        for (String required : new String[] {
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/accounts/{id}/balance",
            "/api/accounts/lookup",
            "/api/transactions/transfer",
            "/api/admin/accounts",
            "/api/admin/dlq",
            "/api/admin/dlq/replay",
            "/api/admin/snapshots/write",
            "/api/admin/projections/rebuild-balances",
            "/api/reconciliation/run",
            "ErrorResponse",
            "traceId"
        }) {
            assertTrue(openapi.contains(required), "Missing OpenAPI contract: " + required);
        }
    }

    @Test
    void protoContainsReadOnlyAccountQueryService() throws Exception {
        String proto = Files.readString(Path.of("src/main/proto/account.proto"));
        assertTrue(proto.contains("service AccountQueryService"));
        assertTrue(proto.contains("rpc GetBalance"));
        assertTrue(proto.contains("rpc LookupAccount"));
        assertTrue(proto.contains("string balance"));
    }

    @Test
    void avroContainsRequiredSagaEvents() throws Exception {
        String avro = Files.readString(Path.of("events/transfer-events.avsc"));
        for (String required : new String[] {
            "TransferInitiated",
            "CompensateDebit",
            "MoneyDebited",
            "MoneyCredited",
            "MoneyDebitFailed",
            "MoneyCreditFailed",
            "MoneyDebitReversed",
            "TransferCompleted",
            "TransferFailed",
            "MoneyMovementEvent",
            "event_type",
            "transaction_id",
            "account_id",
            "amount",
            "currency",
            "correlation_id"
        }) {
            assertTrue(avro.contains(required), "Missing Avro contract token: " + required);
        }
    }
}
