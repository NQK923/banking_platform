package com.ewallet.contracts;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ContractFilesTest {
    @Test
    void openApiContainsFrontendConsumedEndpointsAndErrorShape() throws Exception {
        String openapi = Files.readString(Path.of("openapi.yaml"));
        for (String required : new String[] {
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/accounts/pin/change",
            "PinChangeRequest",
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
    void openApiContainsAllControllerRoutes() throws Exception {
        Set<String> openApiPaths = openApiPaths();
        Set<String> controllerPaths = controllerPaths();
        Set<String> missing = new TreeSet<>(controllerPaths);
        missing.removeAll(openApiPaths);

        assertTrue(
            missing.isEmpty(),
            "Controller routes missing from OpenAPI: " + missing
        );
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

    @Test
    void prometheusAlertsContainReconciliationDriftCondition() throws Exception {
        String alerts = Files.readString(Path.of("../observability/prometheus-alerts.yml"));
        assertTrue(alerts.contains("ReconciliationDriftDetected"));
        assertTrue(alerts.contains("expr: reconciliation_drift > 0"));
    }

    private Set<String> openApiPaths() throws IOException {
        Pattern pathPattern = Pattern.compile("^  (/[^:]+):\\s*$");
        Set<String> paths = new TreeSet<>();
        for (String line : Files.readAllLines(Path.of("openapi.yaml"))) {
            Matcher matcher = pathPattern.matcher(line);
            if (matcher.matches()) {
                paths.add(matcher.group(1));
            }
        }
        return paths;
    }

    private Set<String> controllerPaths() throws IOException {
        Set<String> paths = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of(".."))) {
            for (Path file : files
                .filter(path -> path.toString().endsWith("Controller.java"))
                .filter(path -> !path.toString().contains("\\build\\"))
                .toList()) {
                paths.addAll(controllerPaths(file));
            }
        }
        return paths;
    }

    private Set<String> controllerPaths(Path file) throws IOException {
        String source = Files.readString(file);
        if (!source.contains("@RestController")) {
            return Set.of();
        }

        Matcher classMatcher = Pattern.compile("\\bclass\\s+\\w+").matcher(source);
        int classIndex = classMatcher.find() ? classMatcher.start() : -1;
        String classPrefix = classIndex < 0 ? "" : firstMappingPath(source.substring(0, classIndex));
        Set<String> paths = new TreeSet<>();
        Pattern methodMappingPattern = Pattern.compile(
            "@(GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping|RequestMapping)(?:\\s*\\(([^)]*)\\))?"
        );
        Matcher matcher = methodMappingPattern.matcher(classIndex < 0 ? source : source.substring(classIndex));
        while (matcher.find()) {
            String mapping = matcher.group(1);
            String path = mappingPath(matcher.group(2));
            if ("RequestMapping".equals(mapping) && path.isBlank()) {
                continue;
            }
            paths.add(joinPaths(classPrefix, path));
        }
        return paths;
    }

    private String firstMappingPath(String source) {
        Pattern mappingPattern = Pattern.compile("@RequestMapping(?:\\s*\\(([^)]*)\\))?");
        Matcher matcher = mappingPattern.matcher(source);
        return matcher.find() ? mappingPath(matcher.group(1)) : "";
    }

    private String mappingPath(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("\"([^\"]*)\"").matcher(arguments);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String joinPaths(String prefix, String path) {
        String left = prefix == null ? "" : prefix;
        String right = path == null ? "" : path;
        String joined = (left + "/" + right).replaceAll("/{2,}", "/");
        if (joined.length() > 1 && joined.endsWith("/")) {
            joined = joined.substring(0, joined.length() - 1);
        }
        return joined.isBlank() ? "/" : joined;
    }
}
