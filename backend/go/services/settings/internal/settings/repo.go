package settings

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Snapshot struct {
	JSON      string
	Version   int64
	UpdatedAt time.Time
}

type Repo struct {
	pool *pgxpool.Pool
}

func NewRepo(pool *pgxpool.Pool) *Repo { return &Repo{pool: pool} }

func (r *Repo) Ping(ctx context.Context) error { return r.pool.Ping(ctx) }

func (r *Repo) Read(ctx context.Context, username string) (*Snapshot, error) {
	row := r.pool.QueryRow(ctx, `
		SELECT settings::text, version, updated_at
		FROM user_settings
		WHERE username = $1`, username)
	var s Snapshot
	if err := row.Scan(&s.JSON, &s.Version, &s.UpdatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, nil
		}
		return nil, err
	}
	return &s, nil
}

func (r *Repo) Merge(ctx context.Context, username, newJSON string, expected *int64) (*Snapshot, error) {
	return r.write(ctx, username, newJSON, expected, `
		INSERT INTO user_settings (username, settings, version)
		VALUES ($1, $2::jsonb, 1)
		ON CONFLICT (username) DO UPDATE SET
			settings   = user_settings.settings || EXCLUDED.settings,
			version    = user_settings.version + 1,
			updated_at = now()
		WHERE $3::bigint IS NULL OR user_settings.version = $3::bigint`)
}

func (r *Repo) Replace(ctx context.Context, username, newJSON string, expected *int64) (*Snapshot, error) {
	return r.write(ctx, username, newJSON, expected, `
		INSERT INTO user_settings (username, settings, version)
		VALUES ($1, $2::jsonb, 1)
		ON CONFLICT (username) DO UPDATE SET
			settings   = EXCLUDED.settings,
			version    = user_settings.version + 1,
			updated_at = now()
		WHERE $3::bigint IS NULL OR user_settings.version = $3::bigint`)
}

func (r *Repo) write(ctx context.Context, username, newJSON string, expected *int64, sql string) (*Snapshot, error) {
	tag, err := r.pool.Exec(ctx, sql, username, newJSON, expected)
	if err != nil {
		return nil, err
	}
	if tag.RowsAffected() == 0 {
		return nil, nil
	}
	return r.Read(ctx, username)
}

func (r *Repo) DeleteKey(ctx context.Context, username, key string) (*Snapshot, error) {
	_, err := r.pool.Exec(ctx, `
		UPDATE user_settings
		SET settings = settings - $2, version = version + 1, updated_at = now()
		WHERE username = $1`, username, key)
	if err != nil {
		return nil, err
	}
	return r.Read(ctx, username)
}

func (r *Repo) DeleteAll(ctx context.Context, username string) error {
	_, err := r.pool.Exec(ctx, `DELETE FROM user_settings WHERE username = $1`, username)
	return err
}
