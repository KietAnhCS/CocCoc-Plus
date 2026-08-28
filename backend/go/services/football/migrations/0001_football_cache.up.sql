CREATE SCHEMA IF NOT EXISTS football;

CREATE TABLE IF NOT EXISTS football.api_cache (
    cache_key  TEXT PRIMARY KEY,
    payload    JSONB       NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS api_cache_expires_at_idx ON football.api_cache (expires_at);

CREATE TABLE IF NOT EXISTS football.api_call_log (
    id        BIGSERIAL   PRIMARY KEY,
    endpoint  TEXT        NOT NULL,
    params    TEXT        NOT NULL,
    called_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS api_call_log_called_at_idx ON football.api_call_log (called_at);

CREATE TABLE IF NOT EXISTS football.settings (
    name       TEXT        PRIMARY KEY,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
