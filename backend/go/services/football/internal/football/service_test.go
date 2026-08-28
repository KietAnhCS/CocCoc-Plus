package football

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"
)

type fakeStore struct {
	mu       sync.Mutex
	cache    map[string]*CacheEntry
	calls    []time.Time
	setting  map[string]string
	failFind bool
}

func newFakeStore() *fakeStore {
	return &fakeStore{cache: map[string]*CacheEntry{}, setting: map[string]string{}}
}

func (f *fakeStore) Find(_ context.Context, key string) (*CacheEntry, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failFind {
		return nil, errors.New("db down")
	}
	e, ok := f.cache[key]
	if !ok {
		return nil, nil
	}
	cp := *e
	return &cp, nil
}

func (f *fakeStore) Put(_ context.Context, key, payload string, expiresAt time.Time) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	var exp *time.Time
	if !expiresAt.IsZero() {
		exp = &expiresAt
	}
	f.cache[key] = &CacheEntry{Payload: payload, FetchedAt: time.Now().UTC(), ExpiresAt: exp}
	return nil
}

func (f *fakeStore) RecordCall(_ context.Context, _, _ string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, time.Now().UTC())
	return nil
}

func (f *fakeStore) CallsSince(_ context.Context, since time.Time) (int, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	n := 0
	for _, t := range f.calls {
		if !t.Before(since) {
			n++
		}
	}
	return n, nil
}

func (f *fakeStore) Setting(_ context.Context, name string) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.setting[name], nil
}

func (f *fakeStore) PutSetting(_ context.Context, name, value string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.setting[name] = value
	return nil
}

func (f *fakeStore) Ping(context.Context) error { return nil }

func (f *fakeStore) seedExpired(key, payload string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	fetched := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	expired := time.Date(2026, 1, 2, 0, 0, 0, 0, time.UTC)
	f.cache[key] = &CacheEntry{Payload: payload, FetchedAt: fetched, ExpiresAt: &expired}
}

type fakeProvider struct {
	leagues   []League
	callCount int
	err       error
}

func (p *fakeProvider) Name() string { return "fake" }
func (p *fakeProvider) Leagues(string, string) ([]League, error) {
	p.callCount++
	if p.err != nil {
		return nil, p.err
	}
	return p.leagues, nil
}
func (p *fakeProvider) Fixtures(FixtureQuery) ([]Match, error)       { return nil, nil }
func (p *fakeProvider) Teams(string, string, string) ([]Team, error) { return nil, nil }
func (p *fakeProvider) Players(string) ([]Player, error)             { return nil, nil }
func (p *fakeProvider) Player(string, string) (*Player, error)       { return nil, nil }

func testService(t *testing.T, store Store, prov Provider, key string) *Service {
	t.Helper()
	s := &Service{
		props: Properties{
			APIBaseURL: "https://v3.football.api-sports.io", DailyBudget: 95,
			LiveTTL: 15 * time.Minute, SeasonTTL: 24 * time.Hour, MetadataTTL: 7 * 24 * time.Hour,
		},
		store:  store,
		clock:  func() time.Time { return time.Date(2026, 3, 1, 12, 0, 0, 0, time.UTC) },
		apiKey: key,
	}
	s.provider = prov
	return s
}

func TestLeaguesNoKeyReturnsUnavailable(t *testing.T) {
	s := testService(t, newFakeStore(), &fakeProvider{}, "")
	p := s.Leagues(context.Background(), "", "")
	if p.Source != SourceUnavailable {
		t.Fatalf("source = %q, want unavailable", p.Source)
	}
	if len(p.Data) != 0 {
		t.Fatalf("data = %v, want empty", p.Data)
	}
}

func TestLeaguesLiveThenCache(t *testing.T) {
	store := newFakeStore()
	prov := &fakeProvider{leagues: []League{{ID: "39", Name: "Premier League"}}}
	s := testService(t, store, prov, "k")

	first := s.Leagues(context.Background(), "", "")
	if first.Source != SourceLive || len(first.Data) != 1 {
		t.Fatalf("first = %+v, want live with 1 league", first)
	}
	second := s.Leagues(context.Background(), "", "")
	if second.Source != SourceCache {
		t.Fatalf("second source = %q, want cache", second.Source)
	}
	if prov.callCount != 1 {
		t.Fatalf("provider called %d times, want 1", prov.callCount)
	}
}

func TestExpiredCachePlusProviderErrorFallsBackToStale(t *testing.T) {
	store := newFakeStore()
	store.seedExpired(cacheKey("leagues", "", ""), `[{"id":"39","name":"Premier League"}]`)
	prov := &fakeProvider{err: providerErr("upstream 500")}
	s := testService(t, store, prov, "k")

	p := s.Leagues(context.Background(), "", "")
	if p.Source != SourceStale {
		t.Fatalf("source = %q, want stale", p.Source)
	}
	if len(p.Data) != 1 || p.Data[0].ID != "39" {
		t.Fatalf("data = %+v, want the stale league", p.Data)
	}
	if !p.Stale() {
		t.Fatal("Stale() = false, want true")
	}
}

func TestBudgetExhaustedNoCacheReturnsUnavailable(t *testing.T) {
	store := newFakeStore()
	for i := 0; i < 95; i++ {
		_ = store.RecordCall(context.Background(), "/x", "")
	}
	prov := &fakeProvider{leagues: []League{{ID: "39"}}}
	s := testService(t, store, prov, "k")

	p := s.Leagues(context.Background(), "", "")
	if p.Source != SourceUnavailable {
		t.Fatalf("source = %q, want unavailable", p.Source)
	}
	if prov.callCount != 0 {
		t.Fatalf("provider called %d times, want 0 (budget gate)", prov.callCount)
	}
}

func TestCacheKeyFormat(t *testing.T) {
	got := cacheKey("leagues", "England", "", "Premier League")
	want := "v1:leagues:england:-:premier league"
	if got != want {
		t.Fatalf("cacheKey = %q, want %q", got, want)
	}
}

func TestSeasonTTLPastSeasonUsesMetadataTTL(t *testing.T) {
	s := testService(t, newFakeStore(), &fakeProvider{}, "k")
	if got := s.seasonTTL("2020"); got != s.props.MetadataTTL {
		t.Fatalf("seasonTTL(2020) = %v, want MetadataTTL", got)
	}
	if got := s.seasonTTL("2026"); got != s.props.SeasonTTL {
		t.Fatalf("seasonTTL(2026) = %v, want SeasonTTL", got)
	}
	if got := s.seasonTTL("bogus"); got != s.props.SeasonTTL {
		t.Fatalf("seasonTTL(bogus) = %v, want SeasonTTL", got)
	}
}
