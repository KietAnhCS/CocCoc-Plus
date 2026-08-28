package downloads

import (
	"context"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

type Audit struct {
	pool *pgxpool.Pool
}

func NewAudit(pool *pgxpool.Pool) *Audit { return &Audit{pool: pool} }

func (a *Audit) Record(subject, action, resource, outcome, detail string) {
	slog.Info("audit", "subject", subject, "action", action, "resource", resource, "outcome", outcome)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_, err := a.pool.Exec(ctx, `
		INSERT INTO audit_log (subject, action, resource, outcome, detail)
		VALUES ($1, $2, $3, $4, $5)`,
		nullStr(subject), action, nullStr(resource), outcome, nullStr(detail))
	if err != nil {
		slog.Warn("audit ghi hỏng", "err", err)
	}
}
