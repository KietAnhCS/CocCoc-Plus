package server

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHealthOKAndDown(t *testing.T) {
	r := New(Options{ServiceName: "t", RateLimit: false})
	rec := httptest.NewRecorder()
	r.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/actuator/health", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("code = %d, want 200", rec.Code)
	}

	r2 := New(Options{
		ServiceName: "t", RateLimit: false,
		HealthCheck: func(context.Context) error { return errors.New("db down") },
	})
	rec2 := httptest.NewRecorder()
	r2.ServeHTTP(rec2, httptest.NewRequest(http.MethodGet, "/actuator/health", nil))
	if rec2.Code != http.StatusServiceUnavailable {
		t.Fatalf("code = %d, want 503", rec2.Code)
	}
}

func TestSecurityHeadersApplied(t *testing.T) {
	r := New(Options{ServiceName: "t", RateLimit: false})
	r.Get("/api/x", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(200) })
	rec := httptest.NewRecorder()
	r.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/x", nil))
	if got := rec.Header().Get("X-Frame-Options"); got != "DENY" {
		t.Fatalf("X-Frame-Options = %q, want DENY", got)
	}
	if got := rec.Header().Get("X-Content-Type-Options"); got != "nosniff" {
		t.Fatalf("X-Content-Type-Options = %q", got)
	}
}

func TestCORSPreflightAllowedOrigin(t *testing.T) {
	r := New(Options{ServiceName: "t", RateLimit: false, AllowedOrigins: []string{"http://localhost:5173"}})
	req := httptest.NewRequest(http.MethodOptions, "/api/v1/leagues", nil)
	req.Header.Set("Origin", "http://localhost:5173")
	rec := httptest.NewRecorder()
	r.ServeHTTP(rec, req)
	if rec.Code != http.StatusNoContent {
		t.Fatalf("code = %d, want 204", rec.Code)
	}
	if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "http://localhost:5173" {
		t.Fatalf("ACAO = %q", got)
	}
}
