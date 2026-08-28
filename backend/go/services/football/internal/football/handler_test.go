package football

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func testHandler(key string) *Handler {
	store := newFakeStore()
	prov := &fakeProvider{leagues: []League{{ID: "39", Name: "Premier League"}}}
	svc := testService(&testing.T{}, store, prov, key)
	return NewHandler(svc, func() time.Time { return time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC) })
}

func do(h *Handler, method, target, body string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, target, strings.NewReader(body))
	rec := httptest.NewRecorder()
	h.Routes().ServeHTTP(rec, req)
	return rec
}

func TestFixturesBadDate(t *testing.T) {
	rec := do(testHandler("k"), http.MethodGet, "/fixtures?date=2026-13-40", "")
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("code = %d, want 400", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), "BAD_DATE") {
		t.Fatalf("body = %s, want BAD_DATE", rec.Body.String())
	}
}

func TestFixturesMalformedShape(t *testing.T) {
	rec := do(testHandler("k"), http.MethodGet, "/fixtures?date=hello", "")
	if rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "BAD_DATE") {
		t.Fatalf("code=%d body=%s, want 400 BAD_DATE", rec.Code, rec.Body.String())
	}
}

func TestTeamsMissingFilter(t *testing.T) {
	rec := do(testHandler("k"), http.MethodGet, "/teams", "")
	if rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "MISSING_FILTER") {
		t.Fatalf("code=%d body=%s, want 400 MISSING_FILTER", rec.Code, rec.Body.String())
	}
}

func TestPlayersSearchTooShort(t *testing.T) {
	rec := do(testHandler("k"), http.MethodGet, "/players?search=ab", "")
	if rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "SEARCH_TOO_SHORT") {
		t.Fatalf("code=%d body=%s, want 400 SEARCH_TOO_SHORT", rec.Code, rec.Body.String())
	}
}

func TestPlayerNotFound(t *testing.T) {
	rec := do(testHandler("k"), http.MethodGet, "/players/999?season=2026", "")
	if rec.Code != http.StatusNotFound || !strings.Contains(rec.Body.String(), "PLAYER_NOT_FOUND") {
		t.Fatalf("code=%d body=%s, want 404 PLAYER_NOT_FOUND", rec.Code, rec.Body.String())
	}
}

func TestLeaguesEnvelopeShape(t *testing.T) {
	rec := do(testHandler("k"), http.MethodGet, "/leagues", "")
	if rec.Code != http.StatusOK {
		t.Fatalf("code = %d, want 200", rec.Code)
	}
	var env struct {
		Data []League `json:"data"`
		Meta struct {
			CachedAt time.Time `json:"cachedAt"`
			Source   string    `json:"source"`
			Stale    bool      `json:"stale"`
		} `json:"meta"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &env); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if len(env.Data) != 1 || env.Data[0].ID != "39" {
		t.Fatalf("data = %+v", env.Data)
	}
	if env.Meta.Source != "live" {
		t.Fatalf("meta.source = %q, want live", env.Meta.Source)
	}
}

func TestHealthReportsSampleMode(t *testing.T) {
	rec := do(testHandler(""), http.MethodGet, "/health", "")
	if !strings.Contains(rec.Body.String(), `"sampleOnly":true`) {
		t.Fatalf("body = %s, want sampleOnly true", rec.Body.String())
	}
}
