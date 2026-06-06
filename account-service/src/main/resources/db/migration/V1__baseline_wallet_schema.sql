CREATE TABLE currencies (
    code VARCHAR(10) PRIMARY KEY,
    scale SMALLINT NOT NULL,
    display_name VARCHAR(50) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    phone VARCHAR(20) UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    pin_hash VARCHAR(255) NOT NULL,
    refresh_token_hash VARCHAR(255),
    roles VARCHAR(255) NOT NULL DEFAULT 'ROLE_USER',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED')),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id),
    code VARCHAR(50),
    currency VARCHAR(10) NOT NULL DEFAULT 'VND' REFERENCES currencies(code),
    account_kind VARCHAR(20) NOT NULL DEFAULT 'USER'
        CHECK (account_kind IN ('USER','SYSTEM')),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (user_id, currency),
    UNIQUE (code, currency)
);

CREATE INDEX idx_accounts_user ON accounts(user_id);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    journal_id UUID NOT NULL,
    account_id UUID NOT NULL REFERENCES accounts(id),
    amount NUMERIC(19,4) NOT NULL,
    currency VARCHAR(10) NOT NULL REFERENCES currencies(code),
    entry_type VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT','CREDIT')),
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    CHECK ((entry_type = 'DEBIT' AND amount < 0) OR (entry_type = 'CREDIT' AND amount > 0))
);

CREATE INDEX idx_ledger_account ON ledger_entries(account_id);
CREATE INDEX idx_ledger_journal ON ledger_entries(journal_id);

CREATE TABLE account_events (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    event_type VARCHAR(100) NOT NULL,
    event_data JSONB NOT NULL,
    schema_version INT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL,
    correlation_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (account_id, version)
);

CREATE TABLE transactions (
    id UUID PRIMARY KEY,
    sender_id UUID NOT NULL REFERENCES accounts(id),
    receiver_id UUID NOT NULL REFERENCES accounts(id),
    amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency VARCHAR(10) NOT NULL REFERENCES currencies(code),
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('PENDING','COMPLETED','FAILED','COMPENSATING','CANCELLED')),
    idempotency_key VARCHAR(255) NOT NULL,
    correlation_id UUID,
    debit_applied BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (sender_id, idempotency_key)
);

CREATE INDEX idx_tx_sender ON transactions(sender_id);
CREATE INDEX idx_tx_receiver ON transactions(receiver_id);
CREATE INDEX idx_tx_status ON transactions(status);

CREATE TABLE transaction_outbox (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    correlation_id UUID,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_outbox_unpublished ON transaction_outbox(published, created_at) WHERE published = FALSE;
CREATE INDEX idx_outbox_event_id ON transaction_outbox(event_id);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    entity_type VARCHAR(100) NOT NULL,
    entity_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    actor_type VARCHAR(20) NOT NULL CHECK (actor_type IN ('USER','ADMIN','SYSTEM')),
    actor_id UUID,
    payload JSONB NOT NULL,
    correlation_id UUID,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE account_balances (
    account_id UUID PRIMARY KEY REFERENCES accounts(id),
    balance NUMERIC(19,4) NOT NULL DEFAULT 0,
    currency VARCHAR(10) NOT NULL REFERENCES currencies(code),
    account_kind VARCHAR(20) NOT NULL DEFAULT 'USER'
        CHECK (account_kind IN ('USER','SYSTEM')),
    last_event_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_user_balance_non_negative CHECK (account_kind = 'SYSTEM' OR balance >= 0)
);

CREATE TABLE account_snapshots (
    account_id UUID NOT NULL REFERENCES accounts(id),
    version BIGINT NOT NULL,
    state JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_id, version)
);

CREATE TABLE reconciliation_findings (
    id UUID PRIMARY KEY,
    checked_at TIMESTAMPTZ NOT NULL,
    drift_count INT NOT NULL,
    zero_drift BOOLEAN NOT NULL,
    account_id UUID REFERENCES accounts(id),
    finding VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_reconciliation_findings_checked_at ON reconciliation_findings(checked_at);
CREATE INDEX idx_reconciliation_findings_account ON reconciliation_findings(account_id);

INSERT INTO currencies (code, scale, display_name, is_active) VALUES
    ('VND', 0, 'Vietnamese Dong', TRUE),
    ('USD', 2, 'US Dollar', TRUE);

INSERT INTO accounts (id, user_id, code, currency, account_kind, status, version, created_at) VALUES
    ('00000000-0000-0000-0000-000000000101', NULL, 'CASH_CLEARING', 'VND', 'SYSTEM', 'ACTIVE', 0, now()),
    ('00000000-0000-0000-0000-000000000102', NULL, 'BANK_GATEWAY', 'VND', 'SYSTEM', 'ACTIVE', 0, now()),
    ('00000000-0000-0000-0000-000000000103', NULL, 'SYSTEM_SUSPENSE', 'VND', 'SYSTEM', 'ACTIVE', 0, now());

INSERT INTO account_balances (account_id, balance, currency, account_kind, last_event_version, updated_at) VALUES
    ('00000000-0000-0000-0000-000000000101', 0, 'VND', 'SYSTEM', 0, now()),
    ('00000000-0000-0000-0000-000000000102', 0, 'VND', 'SYSTEM', 0, now()),
    ('00000000-0000-0000-0000-000000000103', 0, 'VND', 'SYSTEM', 0, now());
