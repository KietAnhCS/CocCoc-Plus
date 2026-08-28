CREATE TABLE IF NOT EXISTS downloads (
    id              UUID         NOT NULL,
    username        VARCHAR(32)  NOT NULL,
    source_url      TEXT         NOT NULL,
    file_name       VARCHAR(255) NOT NULL,
    mime_type       VARCHAR(255),
    total_bytes     BIGINT,
    received_bytes  BIGINT       NOT NULL DEFAULT 0,
    state           VARCHAR(16)  NOT NULL,
    local_path      TEXT,
    device_id       VARCHAR(64),
    started_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_downloads PRIMARY KEY (id),
    CONSTRAINT ck_downloads_state
        CHECK (state IN ('IN_PROGRESS', 'PAUSED', 'COMPLETED', 'CANCELLED', 'INTERRUPTED')),
    CONSTRAINT ck_downloads_received CHECK (received_bytes >= 0),
    CONSTRAINT ck_downloads_total CHECK (total_bytes IS NULL OR total_bytes >= received_bytes),
    CONSTRAINT ck_downloads_finished
        CHECK ((state IN ('COMPLETED', 'CANCELLED', 'INTERRUPTED')) = (finished_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS ix_downloads_user_started
    ON downloads (username, started_at DESC);

CREATE INDEX IF NOT EXISTS ix_downloads_dang_chay
    ON downloads (username)
    WHERE state IN ('IN_PROGRESS', 'PAUSED');
