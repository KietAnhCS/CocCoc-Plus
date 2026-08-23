CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    subject     VARCHAR(64),
    action      VARCHAR(64)  NOT NULL,
    resource    VARCHAR(128),
    outcome     VARCHAR(16)  NOT NULL,
    detail      VARCHAR(500)
);

CREATE INDEX IF NOT EXISTS ix_audit_log_subject ON audit_log (subject);
CREATE INDEX IF NOT EXISTS ix_audit_log_occurred_at ON audit_log (occurred_at);
