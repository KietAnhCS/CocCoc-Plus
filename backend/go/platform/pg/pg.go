package pg

import (
	"context"
	"errors"
	"io/fs"
	"net/url"
	"strings"
	"time"

	"github.com/golang-migrate/migrate/v4"
	_ "github.com/golang-migrate/migrate/v4/database/pgx/v5"
	"github.com/golang-migrate/migrate/v4/source/iofs"
	"github.com/jackc/pgx/v5/pgxpool"
)

// DSN builds a libpq/pgx connection string from a base URL (which may carry a
// leading `jdbc:` prefix, as Spring config does) plus separately-supplied
// credentials. Credentials go through url.UserPassword so a password with `@`
// or `:` is percent-encoded instead of breaking the URL.
func DSN(rawURL, user, password, sslmode string) string {
	trimmed := strings.TrimPrefix(strings.TrimSpace(rawURL), "jdbc:")
	u, err := url.Parse(trimmed)
	if err != nil || u.Host == "" {
		return trimmed
	}
	u.Scheme = "postgres"
	if user != "" {
		u.User = url.UserPassword(user, password)
	}
	q := u.Query()
	if sslmode != "" && q.Get("sslmode") == "" {
		q.Set("sslmode", sslmode)
	}
	u.RawQuery = q.Encode()
	return u.String()
}

func Connect(ctx context.Context, dsn string, maxConns int32) (*pgxpool.Pool, error) {
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		return nil, err
	}
	if maxConns > 0 {
		cfg.MaxConns = maxConns
	}
	cfg.MinConns = 1
	cfg.MaxConnIdleTime = 5 * time.Minute

	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, err
	}
	pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := pool.Ping(pingCtx); err != nil {
		pool.Close()
		return nil, err
	}
	return pool, nil
}

func Migrate(dsn string, files fs.FS, dir string) error {
	src, err := iofs.New(files, dir)
	if err != nil {
		return err
	}
	m, err := migrate.NewWithSourceInstance("iofs", src, "pgx5://"+stripScheme(dsn))
	if err != nil {
		return err
	}
	defer m.Close()
	if err := m.Up(); err != nil && !errors.Is(err, migrate.ErrNoChange) {
		return err
	}
	return nil
}

func stripScheme(dsn string) string {
	for _, p := range []string{"postgres://", "postgresql://", "pgx5://", "pgx://"} {
		if strings.HasPrefix(dsn, p) {
			return strings.TrimPrefix(dsn, p)
		}
	}
	return dsn
}
