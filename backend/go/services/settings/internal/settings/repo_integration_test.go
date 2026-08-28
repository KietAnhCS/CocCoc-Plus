//go:build integration

package settings

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

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

func TestRepoMergeReplaceAndOptimisticLock(t *testing.T) {
	pool, _ := itest.PostgresPool(t)
	applySchema(t, pool)
	r := NewRepo(pool)
	ctx := context.Background()

	// First write creates the row at version 1 -> read back version 1.
	s1, err := r.Merge(ctx, "kiet", `{"theme":"dark","size":14}`, nil)
	if err != nil || s1 == nil || s1.Version != 1 {
		t.Fatalf("first merge: %+v err=%v", s1, err)
	}

	// jsonb || merge: existing keys kept, new key added, changed key overwritten.
	s2, err := r.Merge(ctx, "kiet", `{"size":16,"lang":"vi"}`, ptr(int64(1)))
	if err != nil || s2 == nil {
		t.Fatalf("second merge: %v", err)
	}
	if s2.Version != 2 {
		t.Fatalf("version = %d, want 2", s2.Version)
	}
	for _, want := range []string{`"theme": "dark"`, `"size": 16`, `"lang": "vi"`} {
		if !strings.Contains(s2.JSON, want) {
			t.Fatalf("merged json %s missing %s", s2.JSON, want)
		}
	}

	// Stale If-Match must be rejected (0 rows -> nil snapshot).
	stale, err := r.Merge(ctx, "kiet", `{"x":1}`, ptr(int64(1)))
	if err != nil {
		t.Fatal(err)
	}
	if stale != nil {
		t.Fatal("stale version should have been rejected")
	}

	// Replace drops keys not present in the new blob.
	s3, err := r.Replace(ctx, "kiet", `{"only":"this"}`, ptr(int64(2)))
	if err != nil || s3 == nil {
		t.Fatalf("replace: %v", err)
	}
	if strings.Contains(s3.JSON, "theme") || !strings.Contains(s3.JSON, `"only": "this"`) {
		t.Fatalf("replace json = %s", s3.JSON)
	}

	// DeleteKey removes one key and bumps version.
	s4, err := r.DeleteKey(ctx, "kiet", "only")
	if err != nil || s4 == nil {
		t.Fatalf("deleteKey: %v", err)
	}
	if s4.JSON != "{}" || s4.Version != 4 {
		t.Fatalf("after deleteKey: json=%s version=%d", s4.JSON, s4.Version)
	}
}

func TestRepoRejectsNonObjectJSON(t *testing.T) {
	pool, _ := itest.PostgresPool(t)
	applySchema(t, pool)
	r := NewRepo(pool)

	// The ck_user_settings_object CHECK constraint must reject a JSON array.
	if _, err := r.Merge(context.Background(), "kiet", `[1,2,3]`, nil); err == nil {
		t.Fatal("expected CHECK constraint to reject a JSON array")
	}
}

func ptr[T any](v T) *T { return &v }
