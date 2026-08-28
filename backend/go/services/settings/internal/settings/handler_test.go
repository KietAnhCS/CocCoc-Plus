package settings

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/go-chi/chi/v5"

	"github.com/vnsearch/backend-go/platform/auth"
)

type fakeRepo struct {
	snap       *Snapshot
	conflict   bool
	lastMerge  string
	lastExpect *int64
}

func (f *fakeRepo) Read(context.Context, string) (*Snapshot, error) { return f.snap, nil }
func (f *fakeRepo) Merge(_ context.Context, _, j string, e *int64) (*Snapshot, error) {
	f.lastMerge, f.lastExpect = j, e
	if f.conflict {
		return nil, nil
	}
	f.snap = &Snapshot{JSON: j, Version: 2, UpdatedAt: time.Unix(0, 0).UTC()}
	return f.snap, nil
}
func (f *fakeRepo) Replace(_ context.Context, _, j string, e *int64) (*Snapshot, error) {
	if f.conflict {
		return nil, nil
	}
	f.snap = &Snapshot{JSON: j, Version: 2, UpdatedAt: time.Unix(0, 0).UTC()}
	return f.snap, nil
}
func (f *fakeRepo) DeleteKey(_ context.Context, _, _ string) (*Snapshot, error) {
	f.snap = &Snapshot{JSON: "{}", Version: 3, UpdatedAt: time.Unix(0, 0).UTC()}
	return f.snap, nil
}
func (f *fakeRepo) DeleteAll(context.Context, string) error { return nil }

type noAudit struct{}

func (noAudit) Record(_, _, _, _, _ string) {}

func serve(h *Handler, method, target, body string) *httptest.ResponseRecorder {
	r := chi.NewRouter()
	r.Route("/api/settings", func(sub chi.Router) {
		sub.Use(func(next http.Handler) http.Handler {
			return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				ctx := auth.ContextWithIdentity(req.Context(), auth.Identity{Username: "kiet"})
				next.ServeHTTP(w, req.WithContext(ctx))
			})
		})
		h.Register(sub)
	})
	req := httptest.NewRequest(method, target, strings.NewReader(body))
	rec := httptest.NewRecorder()
	r.ServeHTTP(rec, req)
	return rec
}

func TestReadEmpty(t *testing.T) {
	rec := serve(NewHandler(&fakeRepo{}, noAudit{}), http.MethodGet, "/api/settings", "")
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"version":0`) {
		t.Fatalf("code=%d body=%s", rec.Code, rec.Body.String())
	}
}

func TestMergeInvalidJSON(t *testing.T) {
	rec := serve(NewHandler(&fakeRepo{}, noAudit{}), http.MethodPatch, "/api/settings", `[1,2,3]`)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("array body: code=%d, want 400", rec.Code)
	}
	rec = serve(NewHandler(&fakeRepo{}, noAudit{}), http.MethodPatch, "/api/settings", `not json`)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("garbage body: code=%d, want 400", rec.Code)
	}
}

func TestMergeOKSetsETag(t *testing.T) {
	rec := serve(NewHandler(&fakeRepo{}, noAudit{}), http.MethodPatch, "/api/settings", `{"theme":"dark"}`)
	if rec.Code != http.StatusOK {
		t.Fatalf("code=%d body=%s", rec.Code, rec.Body.String())
	}
	if rec.Header().Get("ETag") != `"2"` {
		t.Fatalf("ETag = %q, want \"2\"", rec.Header().Get("ETag"))
	}
}

func TestMergeConflictReturns409(t *testing.T) {
	repo := &fakeRepo{conflict: true, snap: &Snapshot{JSON: `{"a":1}`, Version: 7}}
	rec := serve(NewHandler(repo, noAudit{}), http.MethodPatch, "/api/settings", `{"b":2}`)
	if rec.Code != http.StatusConflict {
		t.Fatalf("code=%d, want 409", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), `"version":7`) || !strings.Contains(rec.Body.String(), `"conflict"`) {
		t.Fatalf("body=%s", rec.Body.String())
	}
}

func TestIfMatchParsedFromHeader(t *testing.T) {
	repo := &fakeRepo{}
	r := chi.NewRouter()
	r.Route("/api/settings", func(sub chi.Router) {
		sub.Use(func(next http.Handler) http.Handler {
			return http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
				next.ServeHTTP(w, req.WithContext(
					auth.ContextWithIdentity(req.Context(), auth.Identity{Username: "kiet"})))
			})
		})
		NewHandler(repo, noAudit{}).Register(sub)
	})
	req := httptest.NewRequest(http.MethodPatch, "/api/settings", strings.NewReader(`{"x":1}`))
	req.Header.Set("If-Match", `"5"`)
	r.ServeHTTP(httptest.NewRecorder(), req)
	if repo.lastExpect == nil || *repo.lastExpect != 5 {
		t.Fatalf("expected version 5, got %v", repo.lastExpect)
	}
}

func TestValidate(t *testing.T) {
	if _, ok := validate([]byte(`{"a":1}`)); !ok {
		t.Fatal("valid object rejected")
	}
	if _, ok := validate([]byte(`  `)); ok {
		t.Fatal("blank accepted")
	}
	if _, ok := validate([]byte(`42`)); ok {
		t.Fatal("number accepted")
	}
}
