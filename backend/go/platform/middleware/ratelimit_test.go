package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestRateLimiterAllowsBurstThenBlocks(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	rl := NewRateLimiter(3, true, false)
	rl.now = func() time.Time { return now }

	h := rl.Handler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	call := func() int {
		req := httptest.NewRequest(http.MethodGet, "/api/v1/leagues", nil)
		req.RemoteAddr = "10.0.0.1:1234"
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		return rec.Code
	}

	for i := 0; i < 3; i++ {
		if code := call(); code != http.StatusOK {
			t.Fatalf("call %d: code = %d, want 200", i, code)
		}
	}
	if code := call(); code != http.StatusTooManyRequests {
		t.Fatalf("4th call: code = %d, want 429", code)
	}

	now = now.Add(20 * time.Second)
	if code := call(); code != http.StatusOK {
		t.Fatalf("after refill: code = %d, want 200", code)
	}
}

func TestRateLimiterDisabledPassesThrough(t *testing.T) {
	rl := NewRateLimiter(1, false, false)
	h := rl.Handler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	for i := 0; i < 10; i++ {
		req := httptest.NewRequest(http.MethodGet, "/api/x", nil)
		req.RemoteAddr = "10.0.0.2:1"
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("call %d blocked with %d", i, rec.Code)
		}
	}
}
