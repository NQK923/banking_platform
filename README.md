# E-Wallet Backend

Java backend for the E-Wallet digital wallet platform. It provides authentication, wallet accounting, transfers, risk evaluation, audit queries, reconciliation, notifications, and administrative APIs.

This repository is one of three application repositories:

- [Backend](https://github.com/NQK923/banking_platform)
- [Admin console](https://github.com/NQK923/banking_platform_admin)
- [Flutter client](https://github.com/NQK923/banking_platform_user)

## Features

- Email/password authentication, HS256 access JWTs, rotating refresh tokens, and password reset by email OTP.
- Separate transaction PIN with failed-attempt tracking and lockout.
- Wallet balances, admin/mock funding, and PIN-protected withdrawals.
- Transfers by email or phone with idempotency, Saga recovery, and credit-failure compensation.
- Deterministic risk evaluation before debit, including warning, step-up, and manual review workflows.
- Account suspension, immutable audit queries, support handling, reconciliation, and notification DLQ operations.

Funding in v1 is an admin/mock operation. Real payment gateways, FX conversion, KYC/AML, statements/export, and account closure are outside the v1 scope.

## Technology stack

Java 17, Spring Boot 3.3.5, Spring Cloud Gateway, Gradle Wrapper, PostgreSQL 16, Flyway, Kafka, Redis 7, gRPC, and JUnit/Testcontainers. The local Compose stack also includes ZooKeeper and Schema Registry.

## Modules

| Module | Responsibility |
| --- | --- |
| `common-domain` | Shared money, status, and error primitives |
| `api-gateway` | Public HTTP routing |
| `account-service` | Auth, accounts, ledger, write-side transfer orchestration, risk, admin operations, outbox, and reconciliation checks |
| `transaction-service` | Public transfer/status/cancel boundary forwarding commands to the account write side |
| `notification-service` | `wallet.events.v1` consumer, mock notification delivery, and DLQ handling |
| `audit-service` | Read-only audit query boundary |
| `reconciliation-service` | Public reconciliation execution boundary |
| `proto` | OpenAPI, Protobuf, and event schema contracts |

## Prerequisites

- JDK 17 available through `JAVA_HOME` or the configured Gradle toolchain.
- PowerShell for the checked-in operational scripts.
- Docker Engine with Compose only for the local stack and container-based tests.

A separate Gradle installation is not required. Commands below use PowerShell from this repository's root; on macOS/Linux, use `./gradlew` in place of `.\gradlew.bat`.

## Build and test without Docker

```powershell
.\gradlew.bat build -PskipDockerTests
```

This builds the modules and runs tests other than those tagged `docker`. Embedded Kafka tests still run. PostgreSQL migration, container-backed Saga, and container Kafka checks are excluded, so this command does not establish full-stack release readiness.

For the complete build and test suite, a working Docker runtime is required:

```powershell
.\gradlew.bat clean build
```

## Configuration

Copy the example configuration, then replace every placeholder:

```powershell
Copy-Item .env.example .env
```

| Variable | Purpose |
| --- | --- |
| `BANKING_POSTGRES_PASSWORD` | Local PostgreSQL password |
| `BANKING_JWT_SECRET` | JWT signing secret; at least 32 UTF-8 bytes |
| `INTERNAL_SERVICE_TOKEN` | Shared credential for internal service requests |
| `BANKING_SEED_ADMIN_PASSWORD` | Password for the seeded `admin@local.test` account |
| `BANKING_SEED_ADMIN_PIN` | Six-digit transaction PIN for the seeded administrator |
| `BANKING_GATEWAY_PORT` | Published gateway port; defaults to `8080` |
| `BANKING_PASSWORD_RESET_OTP_TTL_MINUTES` | Password-reset OTP lifetime; defaults to `10` |
| `BANKING_MAIL_ENABLED` | Enables SMTP delivery; defaults to `false` |
| `BANKING_MAIL_FROM`, `BANKING_MAIL_SMTP_*` | Sender and SMTP settings; see `.env.example` |

Compose reads `.env`; Gradle and standalone Java processes do not automatically import it. Supply the matching environment variables explicitly when running services outside Compose. SMTP must be configured to deliver password-reset emails.

## Run the local stack

Build the service JARs before starting Compose; the stack mounts the generated artifacts:

```powershell
.\gradlew.bat build -PskipDockerTests
docker compose up -d
```

The gateway is available at `http://127.0.0.1:8080`. Only the gateway is published to the host. PostgreSQL, Redis, Kafka, ZooKeeper, Schema Registry, and service ports remain on the private Compose network. The account service's read-only gRPC API listens internally on port `9090`.

To use another gateway port:

```powershell
$env:BANKING_GATEWAY_PORT = '18080'
docker compose up -d
```

Stop the stack with `docker compose down`.

## Smoke tests and local verification

Against an already running gateway, set the seed administrator password in the current shell:

```powershell
$env:BANKING_SEED_ADMIN_PASSWORD = '<your-local-admin-password>'
.\scripts\smoke.ps1 -BaseUrl http://127.0.0.1:8080
```

The smoke script covers registration/login, mock funding, balance lookup, email transfer, insufficient funds, credit-failure compensation, account suspension/audit, and zero-drift reconciliation. It creates and mutates test data; use a disposable development environment.

The combined verifier builds, resets local Compose data, starts the stack, checks gateway readiness, runs smoke tests, and stops the stack:

```powershell
.\scripts\verify-local.ps1 -GatewayPort 18080
```

Use `-KeepData` to reuse existing data, `-KeepRunning` to leave the stack running, or `-SkipBuild` to reuse built artifacts. This verifier uses Docker and resets local data by default.

## Accounting and security contracts

- Monetary values use `NUMERIC(19,4)`/Java `BigDecimal` and decimal strings over JSON/gRPC, validated against each currency's precision.
- Every money movement writes a balanced debit/credit journal. USER wallets must remain non-negative.
- Balance validation belongs to the atomic write side; Redis is a projection/cache.
- Risk scoring happens before debit and does not mutate accounting state.
- Access tokens use standard three-part HS256 JWTs. Legacy two-part tokens require a new login. Refresh rotation rejects concurrent token reuse through a conditional database update.

## API and project documentation

- [OpenAPI contract](proto/openapi.yaml)
- [Protobuf and event contracts](proto/)
- [Operational scripts](scripts/)
- [Admin console setup](https://github.com/NQK923/banking_platform_admin#readme)
- [Flutter client setup](https://github.com/NQK923/banking_platform_user#readme)

The full workspace also contains `DESIGN.md`, `FRONTEND.md`, `AGENTS.md`, and `RELEASE_AUDIT_ISSUES.md` one directory above this checkout. These files and the workspace scripts are maintained separately and are not included when cloning this repository alone. In that workspace, `..\scripts\verify.ps1` runs Docker-free backend, admin, and Flutter checks; the separate `release-e2e.ps1` is the live integration gate.
