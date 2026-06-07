-- Migration to add failed PIN attempts, lock timestamp, transaction note, and failure reason

ALTER TABLE users ADD COLUMN failed_pin_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN pin_locked_until TIMESTAMPTZ;

ALTER TABLE transactions ADD COLUMN note VARCHAR(50);
ALTER TABLE transactions ADD COLUMN failure_reason VARCHAR(255);
