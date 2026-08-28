package football

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type CacheEntry struct {
	Payload   string
	FetchedAt time.Time
	ExpiresAt *time.Time
}

func (e CacheEntry) Expired(now time.Time) bool {
	return e.ExpiresAt != nil && e.ExpiresAt.Before(now)
}

type Store interface {
	Find(ctx context.Context, key string) (*CacheEntry, error)
	Put(ctx context.Context, key, payload string, expiresAt time.Time) error
	RecordCall(ctx context.Context, endpoint, params string) error
	CallsSince(ctx context.Context, since time.Time) (int, error)
	Setting(ctx context.Context, name string) (string, error)
	PutSetting(ctx context.Context, name, value string) error
	Ping(ctx context.Context) error
}

type pgStore struct {
	pool *pgxpool.Pool
}

func NewPGStore(pool *pgxpool.Pool) Store {
	return &pgStore{pool: pool}
}

func (s *pgStore) Find(ctx context.Context, key string) (*CacheEntry, error) {
	row := s.pool.QueryRow(ctx, `
		SELECT payload, fetched_at, expires_at
		FROM football.api_cache
		WHERE cache_key = $1`, key)
	var e CacheEntry
	var expires *time.Time
	if err := row.Scan(&e.Payload, &e.FetchedAt, &expires); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, nil
		}
		return nil, err
	}
	e.ExpiresAt = expires
	return &e, nil
}

func (s *pgStore) Put(ctx context.Context, key, payload string, expiresAt time.Time) error {
	var expires any
	if !expiresAt.IsZero() {
		expires = expiresAt
	}
	_, err := s.pool.Exec(ctx, `
		INSERT INTO football.api_cache (cache_key, payload, fetched_at, expires_at)
		VALUES ($1, $2::jsonb, now(), $3)
		ON CONFLICT (cache_key) DO UPDATE
		SET payload = EXCLUDED.payload,
		    fetched_at = EXCLUDED.fetched_at,
		    expires_at = EXCLUDED.expires_at`, key, payload, expires)
	return err
}

func (s *pgStore) RecordCall(ctx context.Context, endpoint, params string) error {
	_, err := s.pool.Exec(ctx,
		`INSERT INTO football.api_call_log (endpoint, params) VALUES ($1, $2)`, endpoint, params)
	return err
}

func (s *pgStore) CallsSince(ctx context.Context, since time.Time) (int, error) {
	row := s.pool.QueryRow(ctx,
		`SELECT count(*) FROM football.api_call_log WHERE called_at >= $1`, since)
	var n int
	if err := row.Scan(&n); err != nil {
		return 0, err
	}
	return n, nil
}

func (s *pgStore) Setting(ctx context.Context, name string) (string, error) {
	row := s.pool.QueryRow(ctx, `SELECT value FROM football.settings WHERE name = $1`, name)
	var v string
	if err := row.Scan(&v); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return "", nil
		}
		return "", err
	}
	return v, nil
}

func (s *pgStore) PutSetting(ctx context.Context, name, value string) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO football.settings (name, value, updated_at)
		VALUES ($1, $2, now())
		ON CONFLICT (name) DO UPDATE
		SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at`, name, value)
	return err
}

func (s *pgStore) Ping(ctx context.Context) error {
	return s.pool.Ping(ctx)
}
