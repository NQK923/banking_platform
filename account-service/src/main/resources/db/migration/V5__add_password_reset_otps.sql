CREATE TABLE password_reset_otps (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    identifier VARCHAR(255) NOT NULL,
    otp_hash VARCHAR(255) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_password_reset_otps_identifier
    ON password_reset_otps(identifier, created_at DESC);

CREATE INDEX idx_password_reset_otps_user
    ON password_reset_otps(user_id, created_at DESC);
