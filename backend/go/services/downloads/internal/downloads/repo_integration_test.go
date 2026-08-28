//go:build integration

package downloads

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/vnsearch/backend-go/internal/itest"
)

func applySchema(t *testing.T, pool *pgxpool.Pool) {
	t.Helper()
	files, _ := filepath.Glob("../../migrations/*.up.sql")
	for _, f := range files {
		sql, err := os.ReadFile(f)
		if err != nil {
			t.Fatalf("read %s: %v", f, err)
		}
		if _, err := pool.Exec(context.Background(), string(sql)); err != nil {
			t.Fatalf("apply %s: %v", f, err)
		}
	}
}

func TestSaveIsIdempotentAndScopedToUsername(t *testing.T) {
	pool, _ := itest.PostgresPool(t)
	applySchema(t, pool)
	r := NewRepo(pool)
	ctx := context.Background()
	id := uuid.New()

	base := Record{
		ID: id, Username: "kiet", SourceURL: "http://x/f", FileName: "f",
		ReceivedBytes: 0, State: InProgress, StartedAt: time.Now().UTC(),
	}
	if err := r.Save(ctx, base); err != nil {
		t.Fatal(err)
	}

	// Re-save under a DIFFERENT username must not touch the row (WHERE username guard).
	hijack := base
	hijack.Username = "attacker"
	hijack.ReceivedBytes = 999
	if err := r.Save(ctx, hijack); err != nil {
		t.Fatal(err)
	}
	got, err := r.Find(ctx, id, "kiet")
	if err != nil || got == nil {
		t.Fatalf("find: %v", err)
	}
	if got.ReceivedBytes != 0 {
		t.Fatalf("row was modified by another user: receivedBytes=%d", got.ReceivedBytes)
	}
	if other, _ := r.Find(ctx, id, "attacker"); other != nil {
		t.Fatal("attacker should not see the row")
	}
}

func TestTotalBytesCheckConstraint(t *testing.T) {
	pool, _ := itest.PostgresPool(t)
	applySchema(t, pool)
	r := NewRepo(pool)
	ctx := context.Background()

	total := int64(10)
	rec := Record{
		ID: uuid.New(), Username: "kiet", SourceURL: "u", FileName: "f",
		TotalBytes: &total, ReceivedBytes: 50, State: InProgress, StartedAt: time.Now().UTC(),
	}
	if err := r.Save(ctx, rec); err == nil {
		t.Fatal("ck_downloads_total should reject received > total")
	}
}

func TestFindActiveUsesStateFilter(t *testing.T) {
	pool, _ := itest.PostgresPool(t)
	applySchema(t, pool)
	r := NewRepo(pool)
	ctx := context.Background()
	now := time.Now().UTC()

	done := now
	_ = r.Save(ctx, Record{ID: uuid.New(), Username: "kiet", SourceURL: "u", FileName: "a",
		State: Completed, StartedAt: now, FinishedAt: &done})
	_ = r.Save(ctx, Record{ID: uuid.New(), Username: "kiet", SourceURL: "u", FileName: "b",
		State: InProgress, StartedAt: now})
	_ = r.Save(ctx, Record{ID: uuid.New(), Username: "kiet", SourceURL: "u", FileName: "c",
		State: Paused, StartedAt: now})

	active, err := r.FindActive(ctx, "kiet")
	if err != nil {
		t.Fatal(err)
	}
	if len(active) != 2 {
		t.Fatalf("active count = %d, want 2", len(active))
	}

	n, err := r.DeleteFinished(ctx, "kiet")
	if err != nil || n != 1 {
		t.Fatalf("deleteFinished = %d, err=%v, want 1", n, err)
	}
}
