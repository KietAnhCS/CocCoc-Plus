package football

import (
	"context"
	"encoding/json"
	"log/slog"
	"strconv"
	"strings"
	"sync"
	"time"
)

const settingAPIKey = "api_key"

type Source string

const (
	SourceLive        Source = "live"
	SourceCache       Source = "cache"
	SourceStale       Source = "stale"
	SourceUnavailable Source = "unavailable"
)

type Payload[T any] struct {
	Data     T
	Source   Source
	CachedAt time.Time
}

func (p Payload[T]) Stale() bool { return p.Source == SourceStale }

type Service struct {
	props Properties
	store Store
	clock func() time.Time

	mu       sync.RWMutex
	apiKey   string
	provider Provider
}

func NewService(props Properties, store Store, clock func() time.Time) *Service {
	if clock == nil {
		clock = func() time.Time { return time.Now().UTC() }
	}
	s := &Service{props: props, store: store, clock: clock}
	s.applyKey(props.APIKey)
	return s
}

func (s *Service) newProvider(key string) Provider {
	if strings.Contains(s.props.APIBaseURL, liveHost) {
		return newLiveFootballClient(s.props.APIBaseURL, key, s.props.RequestTimeout, recorderFunc(s.RecordCall))
	}
	return newAPIFootballClient(s.props.APIBaseURL, key, s.props.RequestTimeout, recorderFunc(s.RecordCall))
}

func (s *Service) applyKey(key string) {
	p := s.newProvider(key)
	s.mu.Lock()
	s.provider = p
	s.apiKey = key
	s.mu.Unlock()
}

func (s *Service) currentProvider() Provider {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.provider
}

func (s *Service) HasAPIKey() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return strings.TrimSpace(s.apiKey) != ""
}

func (s *Service) ProviderName() string { return s.currentProvider().Name() }

func (s *Service) RestoreAPIKey(ctx context.Context) {
	if strings.TrimSpace(s.props.APIKey) != "" {
		return
	}
	saved, err := s.store.Setting(ctx, settingAPIKey)
	if err != nil {
		slog.Warn("không đọc được khoá đã lưu", "err", err)
		return
	}
	if strings.TrimSpace(saved) != "" {
		s.applyKey(saved)
		slog.Info("đã nạp lại khoá API dán từ giao diện")
	}
}

func (s *Service) SetAPIKey(ctx context.Context, rawKey string) error {
	key := strings.TrimSpace(rawKey)
	if key == "" {
		return providerErr("khoá rỗng")
	}
	if _, err := s.newProvider(key).Leagues("", "Premier League"); err != nil {
		return err
	}
	if err := s.store.PutSetting(ctx, settingAPIKey, key); err != nil {
		slog.Warn("không lưu được khoá, chỉ dùng cho phiên này", "err", err)
	}
	s.applyKey(key)
	return nil
}

func (s *Service) RecordCall(endpoint, params string) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err := s.store.RecordCall(ctx, endpoint, params); err != nil {
		slog.Warn("không ghi được sổ lượt gọi", "endpoint", endpoint, "err", err)
	}
}

func (s *Service) Used(ctx context.Context) (int, error) {
	return s.store.CallsSince(ctx, s.startOfDay())
}

func (s *Service) Budget() int { return s.props.DailyBudget }

func (s *Service) startOfDay() time.Time {
	now := s.clock().UTC()
	return time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, time.UTC)
}

func (s *Service) budgetLeft(ctx context.Context) bool {
	used, err := s.Used(ctx)
	if err != nil {
		slog.Warn("không đếm được hạn mức, tạm coi là đã hết", "err", err)
		return false
	}
	return used < s.Budget()
}

func (s *Service) Leagues(ctx context.Context, country, search string) Payload[[]League] {
	return resolve(s, ctx, cacheKey("leagues", country, search), s.props.MetadataTTL,
		func() ([]League, error) { return s.currentProvider().Leagues(country, search) },
		[]League{})
}

func (s *Service) FixturesByDate(ctx context.Context, date, leagueID, season string) Payload[[]Match] {
	ttl := s.props.LiveTTL
	if d, err := time.Parse("2006-01-02", date); err == nil {
		if d.Before(s.today()) {
			ttl = s.props.MetadataTTL
		}
	}
	return resolve(s, ctx, cacheKey("fixtures:date", date, leagueID, season), ttl,
		func() ([]Match, error) {
			return s.currentProvider().Fixtures(FixtureByDate(date, leagueID, season))
		},
		[]Match{})
}

func (s *Service) TeamFixtures(ctx context.Context, teamID, season, leagueID string) Payload[[]Match] {
	return resolve(s, ctx, cacheKey("fixtures:team", teamID, season, leagueID), s.seasonTTL(season),
		func() ([]Match, error) {
			return s.currentProvider().Fixtures(FixtureByTeam(teamID, season, leagueID))
		},
		[]Match{})
}

func (s *Service) LeagueFixtures(ctx context.Context, leagueID, season string) Payload[[]Match] {
	return resolve(s, ctx, cacheKey("fixtures:league", leagueID, season), s.seasonTTL(season),
		func() ([]Match, error) {
			return s.currentProvider().Fixtures(FixtureByLeague(leagueID, season))
		},
		[]Match{})
}

func (s *Service) Teams(ctx context.Context, search, leagueID, season string) Payload[[]Team] {
	return resolve(s, ctx, cacheKey("teams", search, leagueID, season), s.props.MetadataTTL,
		func() ([]Team, error) { return s.currentProvider().Teams(search, leagueID, season) },
		[]Team{})
}

func (s *Service) Players(ctx context.Context, search string) Payload[[]Player] {
	return resolve(s, ctx, cacheKey("players", search), s.props.MetadataTTL,
		func() ([]Player, error) { return s.currentProvider().Players(search) },
		[]Player{})
}

func (s *Service) Player(ctx context.Context, playerID, season string) Payload[*Player] {
	return resolve(s, ctx, cacheKey("player", playerID, season), s.seasonTTL(season),
		func() (*Player, error) { return s.currentProvider().Player(playerID, season) },
		nil)
}

func (s *Service) today() time.Time {
	now := s.clock().UTC()
	return time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, time.UTC)
}

func (s *Service) seasonTTL(season string) time.Duration {
	year, err := strconv.Atoi(strings.TrimSpace(season))
	if err == nil && year < s.clock().UTC().Year() {
		return s.props.MetadataTTL
	}
	return s.props.SeasonTTL
}

func resolve[T any](s *Service, ctx context.Context, key string, ttl time.Duration,
	live func() (T, error), fallback T) Payload[T] {

	now := s.clock().UTC()

	if !s.HasAPIKey() {
		return Payload[T]{Data: fallback, Source: SourceUnavailable, CachedAt: now}
	}

	entry := s.readCache(ctx, key)

	if entry != nil && !entry.Expired(now) {
		if decoded, ok := decode[T](key, entry); ok {
			return Payload[T]{Data: decoded, Source: SourceCache, CachedAt: entry.FetchedAt}
		}
	}

	if !s.budgetLeft(ctx) {
		if p, ok := stalePayload[T](key, entry); ok {
			return p
		}
		return Payload[T]{Data: fallback, Source: SourceUnavailable, CachedAt: now}
	}

	data, err := live()
	if err != nil {
		slog.Error("gọi nhà cung cấp hỏng", "key", key, "err", err)
		if p, ok := stalePayload[T](key, entry); ok {
			return p
		}
		return Payload[T]{Data: fallback, Source: SourceUnavailable, CachedAt: now}
	}

	s.writeCache(ctx, key, data, now.Add(ttl))
	return Payload[T]{Data: data, Source: SourceLive, CachedAt: now}
}

func stalePayload[T any](key string, entry *CacheEntry) (Payload[T], bool) {
	if entry == nil {
		return Payload[T]{}, false
	}
	if decoded, ok := decode[T](key, entry); ok {
		return Payload[T]{Data: decoded, Source: SourceStale, CachedAt: entry.FetchedAt}, true
	}
	return Payload[T]{}, false
}

func decode[T any](key string, entry *CacheEntry) (T, bool) {
	var out T
	if err := json.Unmarshal([]byte(entry.Payload), &out); err != nil {
		slog.Warn("bản ghi đệm không giải mã được", "key", key, "err", err)
		var zero T
		return zero, false
	}
	return out, true
}

func (s *Service) readCache(ctx context.Context, key string) *CacheEntry {
	entry, err := s.store.Find(ctx, key)
	if err != nil {
		slog.Warn("đọc đệm hỏng, bỏ qua đệm", "key", key, "err", err)
		return nil
	}
	return entry
}

func (s *Service) writeCache(ctx context.Context, key string, data any, expiresAt time.Time) {
	raw, err := json.Marshal(data)
	if err != nil {
		slog.Warn("không mã hoá được đệm", "key", key, "err", err)
		return
	}
	if err := s.store.Put(ctx, key, string(raw), expiresAt); err != nil {
		slog.Warn("không ghi được đệm", "key", key, "err", err)
	}
}

func cacheKey(parts ...string) string {
	var b strings.Builder
	b.WriteString("v1")
	for _, p := range parts {
		cleaned := strings.ToLower(strings.TrimSpace(p))
		b.WriteByte(':')
		if cleaned == "" {
			b.WriteByte('-')
		} else {
			b.WriteString(cleaned)
		}
	}
	return b.String()
}
