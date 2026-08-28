package downloads

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"github.com/vnsearch/backend-go/platform/auth"
)

func TestStateTransitions(t *testing.T) {
	cases := []struct {
		from, to State
		want     bool
	}{
		{InProgress, Completed, true},
		{InProgress, Paused, true},
		{Paused, InProgress, true},
		{Paused, Completed, false},
		{Completed, InProgress, false},
		{Cancelled, InProgress, false},
		{Interrupted, InProgress, true},
		{Interrupted, Completed, false},
		{Completed, Completed, true},
	}
	for _, c := range cases {
		if got := c.from.CanTransitionTo(c.to); got != c.want {
			t.Errorf("%s -> %s = %v, want %v", c.from, c.to, got, c.want)
		}
	}
}

func TestPercent(t *testing.T) {
	total := int64(200)
	r := Record{TotalBytes: &total, ReceivedBytes: 50}
	if p := r.ToPublic("").Percent; p == nil || *p != 25 {
		t.Fatalf("percent = %v, want 25", p)
	}
	if p := (Record{ReceivedBytes: 10}).ToPublic("").Percent; p != nil {
		t.Fatalf("percent = %v, want nil when total unknown", p)
	}
}

type memRepo struct{ m map[uuid.UUID]Record }

func newMemRepo() *memRepo { return &memRepo{m: map[uuid.UUID]Record{}} }

func (r *memRepo) Save(_ context.Context, rec Record) error { r.m[rec.ID] = rec; return nil }
func (r *memRepo) Find(_ context.Context, id uuid.UUID, u string) (*Record, error) {
	rec, ok := r.m[id]
	if !ok || rec.Username != u {
		return nil, nil
	}
	return &rec, nil
}
func (r *memRepo) FindByUser(context.Context, string, int, int) ([]Record, error) { return nil, nil }
func (r *memRepo) FindActive(context.Context, string) ([]Record, error)           { return nil, nil }
func (r *memRepo) Delete(context.Context, uuid.UUID, string) (bool, error)        { return false, nil }
func (r *memRepo) DeleteFinished(context.Context, string) (int, error)            { return 0, nil }
func (r *memRepo) Count(context.Context, string) (int, error)                     { return 0, nil }

func fixedClock() func() time.Time {
	return func() time.Time { return time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC) }
}

func TestStartIsIdempotent(t *testing.T) {
	repo := newMemRepo()
	svc := NewService(repo, fixedClock())
	id := uuid.New()

	first, err := svc.Start(context.Background(), "kiet", id, "http://x/f", "f", "", nil, "", "dev")
	if err != nil {
		t.Fatal(err)
	}
	first.StartedAt = first.StartedAt.Add(time.Hour) // mutate local copy only
	second, err := svc.Start(context.Background(), "kiet", id, "http://x/f", "f", "", nil, "", "dev")
	if err != nil {
		t.Fatal(err)
	}
	if !second.StartedAt.Equal(time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC)) {
		t.Fatalf("second start time changed: %v", second.StartedAt)
	}
	if len(repo.m) != 1 {
		t.Fatalf("expected 1 record, got %d", len(repo.m))
	}
}

func TestUpdateRejectsInvalidTransition(t *testing.T) {
	repo := newMemRepo()
	svc := NewService(repo, fixedClock())
	id := uuid.New()
	_, _ = svc.Start(context.Background(), "kiet", id, "u", "f", "", nil, "", "")

	done := Completed
	if _, err := svc.Update(context.Background(), "kiet", id, nil, &done, nil); err != nil {
		t.Fatalf("IN_PROGRESS -> COMPLETED should succeed: %v", err)
	}
	back := InProgress
	_, err := svc.Update(context.Background(), "kiet", id, nil, &back, nil)
	var it *InvalidTransitionError
	if !errors.As(err, &it) {
		t.Fatalf("COMPLETED -> IN_PROGRESS: err = %v, want InvalidTransitionError", err)
	}
}

func TestUpdateMonotonicBytesAndFinishedAt(t *testing.T) {
	repo := newMemRepo()
	svc := NewService(repo, fixedClock())
	id := uuid.New()
	_, _ = svc.Start(context.Background(), "kiet", id, "u", "f", "", nil, "", "")

	hi := int64(500)
	_, _ = svc.Update(context.Background(), "kiet", id, &hi, nil, nil)
	lo := int64(10)
	rec, _ := svc.Update(context.Background(), "kiet", id, &lo, nil, nil)
	if rec.ReceivedBytes != 500 {
		t.Fatalf("receivedBytes = %d, want 500 (monotonic)", rec.ReceivedBytes)
	}
	done := Completed
	rec, _ = svc.Update(context.Background(), "kiet", id, nil, &done, nil)
	if rec.FinishedAt == nil {
		t.Fatal("terminal state must set finishedAt")
	}
}

func TestHandlerRejectsBadUUID(t *testing.T) {
	h := NewHandler(NewService(newMemRepo(), fixedClock()), noAudit{})
	r := chi.NewRouter()
	r.Route("/api/downloads", func(sub chi.Router) {
		sub.Use(func(next http.Handler) http.Handler {
			return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				next.ServeHTTP(w, req.WithContext(
					auth.ContextWithIdentity(req.Context(), auth.Identity{Username: "kiet"})))
			})
		})
		h.Register(sub)
	})
	req := httptest.NewRequest(http.MethodPatch, "/api/downloads/not-a-uuid", strings.NewReader(`{}`))
	rec := httptest.NewRecorder()
	r.ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("code = %d, want 400", rec.Code)
	}
}

type noAudit struct{}

func (noAudit) Record(_, _, _, _, _ string) {}
