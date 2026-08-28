package football

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

type noopRecorder struct{}

func (noopRecorder) RecordCall(string, string) {}

func TestAPIFootballParsesLeagues(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("x-apisports-key"); got != "secret" {
			t.Errorf("api key header = %q", got)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{
			"errors": [],
			"response": [
				{"league":{"id":39,"name":"Premier League","logo":"pl.png"},
				 "country":{"name":"England","flag":"eng.png"},
				 "seasons":[{"current":true}]}
			]}`))
	}))
	defer srv.Close()

	c := newAPIFootballClient(srv.URL, "secret", 5*time.Second, noopRecorder{})
	leagues, err := c.Leagues("", "")
	if err != nil {
		t.Fatalf("Leagues: %v", err)
	}
	if len(leagues) != 1 {
		t.Fatalf("got %d leagues", len(leagues))
	}
	l := leagues[0]
	if l.ID != "39" || l.Name != "Premier League" || l.Country != "England" || l.Status != "Active" {
		t.Fatalf("league = %+v", l)
	}
}

func TestAPIFootballSurfacesUpstreamError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"errors":{"token":"invalid api key"},"response":[]}`))
	}))
	defer srv.Close()

	c := newAPIFootballClient(srv.URL, "secret", 5*time.Second, noopRecorder{})
	_, err := c.Leagues("", "")
	if err == nil {
		t.Fatal("expected error, got nil")
	}
	var pe *ProviderError
	if !asProviderError(err, &pe) {
		t.Fatalf("error type = %T, want *ProviderError", err)
	}
}

func TestAPIFootballParsesFixtureStatusAndScores(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{
			"errors": [],
			"response": [
				{"fixture":{"id":1,"date":"2026-03-01T15:00:00+00:00","status":{"short":"2H","elapsed":57}},
				 "league":{"id":39,"name":"Premier League","round":"Regular Season - 28","logo":"pl.png"},
				 "teams":{"home":{"id":33,"name":"Man United","logo":"mu.png"},
				          "away":{"id":40,"name":"Liverpool","logo":"lfc.png"}},
				 "goals":{"home":1,"away":2}}
			]}`))
	}))
	defer srv.Close()

	c := newAPIFootballClient(srv.URL, "secret", 5*time.Second, noopRecorder{})
	matches, err := c.Fixtures(FixtureByDate("2026-03-01", "", ""))
	if err != nil {
		t.Fatalf("Fixtures: %v", err)
	}
	if len(matches) != 1 {
		t.Fatalf("got %d matches", len(matches))
	}
	m := matches[0]
	if m.Status != StatusLive {
		t.Fatalf("status = %q, want live", m.Status)
	}
	if m.Elapsed == nil || *m.Elapsed != 57 {
		t.Fatalf("elapsed = %v, want 57", m.Elapsed)
	}
	if m.HomeScore == nil || *m.HomeScore != 1 || m.AwayScore == nil || *m.AwayScore != 2 {
		t.Fatalf("score = %v-%v", m.HomeScore, m.AwayScore)
	}
	if m.Kickoff == nil || !m.Kickoff.Equal(time.Date(2026, 3, 1, 15, 0, 0, 0, time.UTC)) {
		t.Fatalf("kickoff = %v", m.Kickoff)
	}
}

func asProviderError(err error, target **ProviderError) bool {
	for err != nil {
		if pe, ok := err.(*ProviderError); ok {
			*target = pe
			return true
		}
		type unwrapper interface{ Unwrap() error }
		u, ok := err.(unwrapper)
		if !ok {
			return false
		}
		err = u.Unwrap()
	}
	return false
}
