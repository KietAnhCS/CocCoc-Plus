CREATE TABLE IF NOT EXISTS api_cache (
    cache_key  TEXT PRIMARY KEY,
    payload    JSONB       NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS api_cache_expires_at_idx ON api_cache (expires_at);

CREATE TABLE IF NOT EXISTS api_call_log (
    id        BIGSERIAL   PRIMARY KEY,
    endpoint  TEXT        NOT NULL,
    params    TEXT        NOT NULL,
    called_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS api_call_log_called_at_idx ON api_call_log (called_at);

CREATE TABLE IF NOT EXISTS settings (
    name       TEXT        PRIMARY KEY,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
