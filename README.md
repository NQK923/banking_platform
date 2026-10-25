# Banking Platform Backend

Spring Boot multi-module backend for the digital wallet platform described in `../AGENTS.md`, `../DESIGN.md`, and `../FRONTEND.md`.

## Modules

- `common-domain`: shared money, status, and error primitives.
- `api-gateway`: Spring Cloud Gateway routes for auth, accounts, transactions, audit, and reconciliation.
- `account-service`: v1 command/write owner for auth, accounts, ledger, transfers, admin, gRPC lookup, outbox publishing, and reconciliation checks.
- `transaction-service`: public transaction API boundary forwarding transfer/status/cancel commands to the account write side.
- `notification-service`: Kafka consumer for `wallet.events.v1` with v1 mock notification logging.
- `audit-service`: public immutable audit query boundary.
- `reconciliation-service`: public reconciliation run boundary.
- `proto`: OpenAPI, Protobuf, and event schema contracts.

## Local Infrastructure

```powershell
docker compose up -d
```

Only the API Gateway is published to the host (`127.0.0.1:8080` by default). Postgres, Redis, Kafka, ZooKeeper, and Schema Registry stay on the private Compose network to avoid port collisions with other local projects. Override the gateway port with `BANKING_GATEWAY_PORT` when needed.

## Build And Test

The repo includes the standard Gradle wrapper.

```powershell
.\gradlew.bat clean build
```

To verify without starting or contacting Docker:

```powershell
.\gradlew.bat build -PskipDockerTests
```

This excludes JUnit tests tagged `docker`, including PostgreSQL migrations, transfer Saga integration and Kafka container contracts. Unit tests and embedded Kafka notification/DLQ tests still run. It does not replace the full release gate. From the workspace root, `scripts/verify.ps1` also runs admin lint/tests/build and Flutter analysis/tests/web release build without Docker.

Access tokens use standard HS256 JWT with a signing secret of at least 32 UTF-8 bytes. Older two-part tokens are invalid; log in again after upgrading. Refresh-token rotation uses an atomic conditional database update to reject concurrent reuse.

## Smoke Test

```powershell
.\gradlew.bat clean build
docker compose up -d
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke.ps1 -BaseUrl http://localhost:8080
```

Or run the combined verifier:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-local.ps1
```

Use `-GatewayPort 18080` to run the verifier alongside another stack already using port 8080.

The verifier resets the local Compose project data by default so Postgres credentials and Flyway state are reproducible, runs the full stack in Compose, checks service health with the internal service token, executes smoke through the Gateway, then stops the stack. Pass `-KeepData` to reuse existing local data and `-KeepRunning` to leave the stack running.

The smoke script covers register/login, mock deposit, balance, transfer by email, insufficient funds, credit-fail compensation, admin suspend/audit, and reconciliation zero drift.

`account-service` also exposes read-only gRPC `AccountQueryService` on port `9090`.
