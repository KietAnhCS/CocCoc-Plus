CREATE TABLE IF NOT EXISTS user_settings (
    username    VARCHAR(32)  NOT NULL,
    settings    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    version     BIGINT       NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_user_settings PRIMARY KEY (username),
    CONSTRAINT ck_user_settings_size CHECK (pg_column_size(settings) <= 65536),
    CONSTRAINT ck_user_settings_object CHECK (jsonb_typeof(settings) = 'object')
);
