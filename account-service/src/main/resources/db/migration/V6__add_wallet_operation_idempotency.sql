CREATE TABLE wallet_operation_idempotency (
    account_id UUID NOT NULL REFERENCES accounts(id),
    operation_type VARCHAR(20) NOT NULL CHECK (operation_type IN ('DEPOSIT','WITHDRAW')),
    idempotency_key VARCHAR(255) NOT NULL,
    payload_hash VARCHAR(128) NOT NULL,
    journal_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_id, operation_type, idempotency_key)
);

CREATE INDEX idx_wallet_operation_idempotency_created_at
    ON wallet_operation_idempotency(created_at);
