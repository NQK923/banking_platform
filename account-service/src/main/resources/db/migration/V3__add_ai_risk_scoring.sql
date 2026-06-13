CREATE TABLE ai_risk_evaluations (
    id UUID PRIMARY KEY,
    transaction_id UUID REFERENCES transactions(id),
    sender_account_id UUID NOT NULL REFERENCES accounts(id),
    receiver_account_id UUID REFERENCES accounts(id),
    idempotency_key VARCHAR(255) NOT NULL,
    payload_hash VARCHAR(128) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency VARCHAR(10) NOT NULL REFERENCES currencies(code),
    risk_score INT NOT NULL CHECK (risk_score BETWEEN 0 AND 100),
    risk_level VARCHAR(20) NOT NULL CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    recommended_action VARCHAR(50) NOT NULL CHECK (recommended_action IN ('ALLOW', 'WARN_USER', 'STEP_UP_AUTH', 'MANUAL_REVIEW', 'BLOCK')),
    reasons JSONB NOT NULL,
    features JSONB NOT NULL,
    model_version VARCHAR(100) NOT NULL,
    policy_version VARCHAR(100) NOT NULL,
    decision_status VARCHAR(30) NOT NULL DEFAULT 'EVALUATED'
        CHECK (decision_status IN (
            'EVALUATED',
            'USER_ACKNOWLEDGED',
            'STEP_UP_REQUIRED',
            'STEP_UP_PASSED',
            'STEP_UP_FAILED',
            'MANUAL_REVIEW_REQUIRED',
            'MANUAL_APPROVED',
            'MANUAL_REJECTED',
            'BLOCKED'
        )),
    trace_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (sender_account_id, idempotency_key)
);

CREATE INDEX idx_ai_risk_tx ON ai_risk_evaluations(transaction_id);
CREATE INDEX idx_ai_risk_sender ON ai_risk_evaluations(sender_account_id);
CREATE INDEX idx_ai_risk_receiver ON ai_risk_evaluations(receiver_account_id);
CREATE INDEX idx_ai_risk_level ON ai_risk_evaluations(risk_level, recommended_action);
CREATE INDEX idx_ai_risk_created_at ON ai_risk_evaluations(created_at);

CREATE TABLE risk_feature_snapshots (
    id UUID PRIMARY KEY,
    risk_evaluation_id UUID NOT NULL REFERENCES ai_risk_evaluations(id),
    sender_account_id UUID NOT NULL REFERENCES accounts(id),
    receiver_account_id UUID REFERENCES accounts(id),
    feature_version VARCHAR(100) NOT NULL,
    features JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_risk_feature_snapshot_eval ON risk_feature_snapshots(risk_evaluation_id);
CREATE INDEX idx_risk_feature_snapshot_sender ON risk_feature_snapshots(sender_account_id);

CREATE TABLE risk_review_actions (
    id UUID PRIMARY KEY,
    risk_evaluation_id UUID NOT NULL REFERENCES ai_risk_evaluations(id),
    transaction_id UUID REFERENCES transactions(id),
    action VARCHAR(30) NOT NULL CHECK (action IN ('APPROVE', 'REJECT', 'ESCALATE')),
    reason VARCHAR(500),
    actor_id UUID NOT NULL,
    actor_role VARCHAR(50) NOT NULL,
    trace_id UUID,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_risk_review_eval ON risk_review_actions(risk_evaluation_id);
CREATE INDEX idx_risk_review_actor ON risk_review_actions(actor_id);

ALTER TABLE transactions ADD COLUMN review_status VARCHAR(30)
    CHECK (review_status IN (
        'NONE',
        'WARNING_ACKNOWLEDGED',
        'STEP_UP_PASSED',
        'MANUAL_REVIEW_REQUIRED',
        'MANUAL_APPROVED',
        'MANUAL_REJECTED',
        'BLOCKED'
    ));
ALTER TABLE transactions ADD COLUMN risk_evaluation_id UUID REFERENCES ai_risk_evaluations(id);

CREATE INDEX idx_tx_review_status ON transactions(review_status);
CREATE INDEX idx_tx_risk_evaluation ON transactions(risk_evaluation_id);
