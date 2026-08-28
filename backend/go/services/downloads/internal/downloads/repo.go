package downloads

import (
	"context"
	"errors"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const columns = `id, username, source_url, file_name, mime_type, total_bytes, received_bytes,
	state, local_path, device_id, started_at, finished_at, updated_at`

type Repo struct {
	pool *pgxpool.Pool
}

func NewRepo(pool *pgxpool.Pool) *Repo { return &Repo{pool: pool} }

func (r *Repo) Ping(ctx context.Context) error { return r.pool.Ping(ctx) }

func (r *Repo) Save(ctx context.Context, rec Record) error {
	_, err := r.pool.Exec(ctx, `
		INSERT INTO downloads
			(id, username, source_url, file_name, mime_type, total_bytes,
			 received_bytes, state, local_path, device_id, started_at, finished_at, updated_at)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12, now())
		ON CONFLICT (id) DO UPDATE SET
			received_bytes = EXCLUDED.received_bytes,
			total_bytes    = COALESCE(EXCLUDED.total_bytes, downloads.total_bytes),
			state          = EXCLUDED.state,
			local_path     = COALESCE(EXCLUDED.local_path, downloads.local_path),
			finished_at    = EXCLUDED.finished_at,
			updated_at     = now()
		WHERE downloads.username = EXCLUDED.username`,
		rec.ID, rec.Username, rec.SourceURL, rec.FileName, nullStr(rec.MimeType),
		rec.TotalBytes, rec.ReceivedBytes, string(rec.State), nullStr(rec.LocalPath),
		nullStr(rec.DeviceID), rec.StartedAt, rec.FinishedAt)
	return err
}

func (r *Repo) Find(ctx context.Context, id uuid.UUID, username string) (*Record, error) {
	row := r.pool.QueryRow(ctx, `SELECT `+columns+` FROM downloads WHERE id = $1 AND username = $2`, id, username)
	rec, err := scan(row)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	return rec, err
}

func (r *Repo) FindByUser(ctx context.Context, username string, offset, limit int) ([]Record, error) {
	rows, err := r.pool.Query(ctx, `SELECT `+columns+`
		FROM downloads WHERE username = $1
		ORDER BY started_at DESC LIMIT $2 OFFSET $3`, username, limit, offset)
	if err != nil {
		return nil, err
	}
	return collect(rows)
}

func (r *Repo) FindActive(ctx context.Context, username string) ([]Record, error) {
	rows, err := r.pool.Query(ctx, `SELECT `+columns+`
		FROM downloads WHERE username = $1 AND state IN ('IN_PROGRESS','PAUSED')
		ORDER BY started_at DESC`, username)
	if err != nil {
		return nil, err
	}
	return collect(rows)
}

func (r *Repo) Delete(ctx context.Context, id uuid.UUID, username string) (bool, error) {
	tag, err := r.pool.Exec(ctx, `DELETE FROM downloads WHERE id = $1 AND username = $2`, id, username)
	return tag.RowsAffected() > 0, err
}

func (r *Repo) DeleteFinished(ctx context.Context, username string) (int, error) {
	tag, err := r.pool.Exec(ctx, `
		DELETE FROM downloads
		WHERE username = $1 AND state IN ('COMPLETED','CANCELLED','INTERRUPTED')`, username)
	return int(tag.RowsAffected()), err
}

func (r *Repo) Count(ctx context.Context, username string) (int, error) {
	var n int
	err := r.pool.QueryRow(ctx, `SELECT count(*) FROM downloads WHERE username = $1`, username).Scan(&n)
	return n, err
}

type scanner interface {
	Scan(dest ...any) error
}

func scan(s scanner) (*Record, error) {
	var rec Record
	var mime, localPath, deviceID *string
	if err := s.Scan(&rec.ID, &rec.Username, &rec.SourceURL, &rec.FileName, &mime,
		&rec.TotalBytes, &rec.ReceivedBytes, &rec.State, &localPath, &deviceID,
		&rec.StartedAt, &rec.FinishedAt, &rec.UpdatedAt); err != nil {
		return nil, err
	}
	rec.MimeType = deref(mime)
	rec.LocalPath = deref(localPath)
	rec.DeviceID = deref(deviceID)
	return &rec, nil
}

func collect(rows pgx.Rows) ([]Record, error) {
	defer rows.Close()
	var out []Record
	for rows.Next() {
		rec, err := scan(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, *rec)
	}
	return out, rows.Err()
}

func nullStr(s string) any {
	if s == "" {
		return nil
	}
	return s
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}
