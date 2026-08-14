// Package service ghép ba thứ lại: client API-Football, bộ nhớ đệm Postgres,
// và hạn mức gọi ra ngoài.
//
// Đây là nơi giữ CHÍNH SÁCH. Client chỉ biết gọi HTTP, store chỉ biết đọc ghi
// bảng; còn "hỏi lại nhà cung cấp sau bao lâu", "hết hạn mức thì làm gì" là
// những quyết định của hệ thống này và chúng nằm gọn ở đây.
package service

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/KietAnhCS/coccoc-browser-like/football-service/internal/apifootball"
	"github.com/KietAnhCS/coccoc-browser-like/football-service/internal/config"
	"github.com/KietAnhCS/coccoc-browser-like/football-service/internal/football"
	"github.com/KietAnhCS/coccoc-browser-like/football-service/internal/livefootball"
	"github.com/KietAnhCS/coccoc-browser-like/football-service/internal/store"
)

// Nguồn của một phản hồi, đi kèm trong meta.source để người gọi biết mình đang
// nhìn dữ liệu gì.
const (
	SourceLive  = "live"  // vừa hỏi nhà cung cấp xong
	SourceCache = "cache" // lấy từ đệm, còn hạn
	SourceStale = "stale" // lấy từ đệm ĐÃ QUÁ HẠN, vì không làm mới được
	// SourceUnavailable: không lấy được gì cả — chưa có khoá, hoặc nhà cung
	// cấp lỗi và trong đệm cũng không có bản cũ nào.
	//
	// Thay cho "sample" của bản trước. Service từng bịa ra một lịch thi đấu để
	// giao diện có cái mà vẽ; bỏ hẳn vì một bảng tỉ số hiện những trận không hề
	// tồn tại thì tệ hơn hẳn một bảng trống nói rõ là chưa có dữ liệu.
	SourceUnavailable = "unavailable"
)

// Thời gian sống của từng loại dữ liệu.
const (
	// ttlMetadata cho những thứ gần như bất động: danh sách giải, hồ sơ đội.
	ttlMetadata = 7 * 24 * time.Hour

	// ttlSeason cho lịch thi đấu của mùa đang diễn ra.
	ttlSeason = 24 * time.Hour
)

// defaultLiveTTL là thời gian sống của lịch thi đấu TRONG NGÀY.
//
// 15 phút không phải con số chọn cho đẹp mà là phép chia: gói miễn phí cho 100
// lượt/ngày, ta chừa lại 5 nên còn 95, và 24 giờ chia cho 95 ra 15,2 phút. Đặt
// ngắn hơn thì một bảng bên mở suốt ngày sẽ tự đốt hết hạn mức trước giờ
// chiều, rồi mọi người dùng khác chỉ còn dữ liệu cũ.
//
// Nói thẳng ra: gói miễn phí KHÔNG đủ cho tỉ số trực tiếp thật sự. Cách trung
// thực nhất là làm mới mỗi 15 phút và ghi rõ mốc `cachedAt` trong phản hồi, để
// giao diện nói được "cập nhật lúc 20:15" thay vì giả vờ là thời gian thực.
const defaultLiveTTL = 15 * time.Minute

// settingAPIKey là tên bản ghi cấu hình giữ khoá dán từ giao diện.
const settingAPIKey = "api_key"

// Service phục vụ dữ liệu bóng đá.
type Service struct {
	cfg     config.Config
	store   *store.Store
	log     *slog.Logger
	liveTTL time.Duration

	// mu chỉ bảo vệ apiKey và client. Khoá có thể bị đổi giữa chừng bởi một
	// request PUT trong khi hàng chục request khác đang đọc — không có khoá
	// này thì đó là một cuộc đua thật sự, và `go test -race` sẽ chỉ ra ngay.
	mu     sync.RWMutex
	apiKey string
	client football.Provider

	// now tách ra được để kiểm thử có thể đóng băng thời gian.
	now func() time.Time
}

// New dựng service. store có thể là nil khi chạy hoàn toàn bằng dữ liệu mẫu.
func New(cfg config.Config, st *store.Store, log *slog.Logger) *Service {
	svc := &Service{
		cfg:     cfg,
		store:   st,
		log:     log,
		liveTTL: defaultLiveTTL,
		now:     time.Now,
	}
	svc.setKey(cfg.APIKey)
	return svc
}

// newProvider chọn client theo địa chỉ đang cấu hình.
//
// Suy ra từ chính địa chỉ thay vì bắt người dùng khai thêm một biến: hai giá
// trị ấy không bao giờ mâu thuẫn được, còn một biến rời chỉ thêm một chỗ để
// cấu hình sai — và cấu hình sai ở đây hiện ra thành JSON giải mã hỏng, một
// triệu chứng chẳng trỏ về đâu cả.
func (s *Service) newProvider(key string) football.Provider {
	if strings.Contains(s.cfg.APIBaseURL, livefootball.Host) {
		return livefootball.New(s.cfg.APIBaseURL, key, s.cfg.RequestTimeout, s)
	}
	return apifootball.New(s.cfg.APIBaseURL, key, s.cfg.RequestTimeout, s)
}

// setKey thay khoá và dựng lại client. Người gọi KHÔNG được giữ svc.mu.
func (s *Service) setKey(key string) {
	client := s.newProvider(key)

	s.mu.Lock()
	defer s.mu.Unlock()
	s.apiKey, s.client = key, client
}

// apiClient trả về client hiện hành.
func (s *Service) apiClient() football.Provider {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.client
}

// ProviderName là tên nhà cung cấp đang dùng, để giao diện nói đúng nguồn.
//
// Suy từ địa chỉ chứ không lưu thành một trường: địa chỉ là thứ duy nhất quyết
// định ta gọi đi đâu, nên nó cũng là nguồn sự thật duy nhất cho câu hỏi này.
func (s *Service) ProviderName() string {
	if strings.Contains(s.cfg.APIBaseURL, livefootball.Host) {
		return "free-api-live-football-data (RapidAPI)"
	}
	return "API-Football"
}

// HasAPIKey cho biết đang chạy bằng dữ liệu thật hay dữ liệu mẫu.
//
// Thay cho cfg.SampleOnly() ở mọi chỗ cần biết điều này: từ khi khoá dán được
// từ giao diện, biến môi trường lúc khởi động không còn là câu trả lời đúng.
func (s *Service) HasAPIKey() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.apiKey != ""
}

// RestoreAPIKey nạp lại khoá đã dán ở lần chạy trước.
//
// Biến môi trường THẮNG bản lưu: ai đặt FOOTBALL_API_KEY trong .env là đang
// nói rõ mình muốn dùng khoá nào, và để một giá trị cũ trong CSDL đè lên đó là
// cách chắc chắn nhất để người ta sửa .env mãi mà không thấy gì đổi.
func (s *Service) RestoreAPIKey(ctx context.Context) {
	if s.store == nil || s.cfg.APIKey != "" {
		return
	}
	key, err := s.store.Setting(ctx, settingAPIKey)
	if err != nil {
		s.log.Warn("không đọc được khoá đã lưu", "err", err)
		return
	}
	if key != "" {
		s.setKey(key)
		s.log.Info("đã nạp lại khoá API dán từ giao diện")
	}
}

// SetAPIKey kiểm tra khoá rồi mới nhận.
//
// Kiểm tra TRƯỚC khi lưu, và đó là cả giá trị của hàm này: dán nhầm một khoá
// chưa đăng ký gói sẽ nhận về 403 ngay tại chỗ, kèm câu giải thích, thay vì
// một giao diện im lặng trả về dữ liệu mẫu mãi mà không ai hiểu vì sao.
func (s *Service) SetAPIKey(ctx context.Context, key string) error {
	key = strings.TrimSpace(key)
	if key == "" {
		return fmt.Errorf("khoá rỗng")
	}

	probe := s.newProvider(key)
	if _, err := probe.Leagues(ctx, "", "Premier League"); err != nil {
		return err
	}

	if s.store != nil {
		if err := s.store.PutSetting(ctx, settingAPIKey, key); err != nil {
			// Lưu hỏng thì vẫn nhận khoá cho phiên này: người dùng vừa chứng
			// minh được khoá chạy, bắt họ dán lại vì một lỗi ghi CSDL là phạt
			// nhầm người.
			s.log.Warn("không lưu được khoá, chỉ dùng cho phiên này", "err", err)
		}
	}

	s.setKey(key)
	return nil
}

// RecordCall hiện thực apifootball.Recorder.
//
// Nuốt lỗi có chủ ý: sổ đếm hỏng thì cùng lắm là ta gọi ra ngoài nhiều hơn dự
// định, còn để lỗi ấy làm hỏng cả phản hồi thì người dùng mất dữ liệu vì một
// việc thuần kế toán.
func (s *Service) RecordCall(ctx context.Context, endpoint, params string) {
	if s.store == nil {
		return
	}
	if err := s.store.RecordCall(ctx, endpoint, params); err != nil {
		s.log.Warn("không ghi được sổ lượt gọi", "endpoint", endpoint, "err", err)
	}
}

// Usage trả về số lượt đã dùng trong ngày UTC hiện tại và hạn mức.
func (s *Service) Usage(ctx context.Context) (used int, budget int, err error) {
	if s.store == nil {
		return 0, s.cfg.DailyBudget, nil
	}
	used, err = s.store.CallsSince(ctx, s.startOfDay())
	return used, s.cfg.DailyBudget, err
}

func (s *Service) startOfDay() time.Time {
	now := s.now().UTC()
	return time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, time.UTC)
}

// budgetLeft cho biết còn được gọi ra ngoài hay không.
func (s *Service) budgetLeft(ctx context.Context) bool {
	if s.store == nil {
		return false
	}
	used, err := s.store.CallsSince(ctx, s.startOfDay())
	if err != nil {
		// Không đếm được thì coi như HẾT hạn mức. Đoán sai theo hướng này chỉ
		// làm dữ liệu cũ đi; đoán sai theo hướng kia có thể làm khoá bị nhà
		// cung cấp khoá cả ngày.
		s.log.Warn("không đếm được hạn mức, tạm coi là đã hết", "err", err)
		return false
	}
	return used < s.cfg.DailyBudget
}

// Payload là kết quả kèm xuất xứ.
type Payload[T any] struct {
	Data     T
	Source   string
	CachedAt time.Time
}

// resolve là toàn bộ quy trình lấy dữ liệu, dùng chung cho mọi endpoint.
//
// Thứ tự ưu tiên, và lý do của nó:
//
//  1. Chưa có khoá        → dữ liệu mẫu. Không đụng vào đệm: ghi dữ liệu mẫu
//     vào đệm sẽ đầu độc nó, và ngày ai đó cắm khoá thật vào thì service vẫn
//     trả dữ liệu bịa cho tới khi hết hạn.
//  2. Đệm còn hạn         → trả luôn.
//  3. Hết hạn mức         → trả đệm QUÁ HẠN nếu có, không thì dữ liệu mẫu.
//  4. Gọi ra ngoài lỗi    → cũng vậy.
//
// Bước 3 và 4 là chỗ khác bản TypeScript gốc: bản đó ném lỗi và trả 500. Một
// bảng tỉ số hiện "cập nhật lúc 19:40" hữu ích hơn hẳn một bảng trống báo lỗi
// máy chủ, nhất là khi dữ liệu cũ vẫn nằm sẵn trong CSDL.
func resolve[T any](
	ctx context.Context,
	s *Service,
	cacheKey string,
	ttl time.Duration,
	live func(context.Context) (T, error),
	fallback func() T,
) Payload[T] {
	now := s.now()

	if !s.HasAPIKey() || s.store == nil {
		return Payload[T]{Data: fallback(), Source: SourceUnavailable, CachedAt: now}
	}

	entry, found, err := s.store.Get(ctx, cacheKey)
	if err != nil {
		s.log.Warn("đọc đệm hỏng, bỏ qua đệm", "key", cacheKey, "err", err)
	}

	if found && !entry.Expired(now) {
		var data T
		if err := json.Unmarshal(entry.Payload, &data); err == nil {
			return Payload[T]{Data: data, Source: SourceCache, CachedAt: entry.FetchedAt}
		}
		s.log.Warn("bản ghi đệm không giải mã được, coi như thiếu", "key", cacheKey, "err", err)
	}

	stale := func() (Payload[T], bool) {
		if !found {
			return Payload[T]{}, false
		}
		var data T
		if err := json.Unmarshal(entry.Payload, &data); err != nil {
			return Payload[T]{}, false
		}
		return Payload[T]{Data: data, Source: SourceStale, CachedAt: entry.FetchedAt}, true
	}

	if !s.budgetLeft(ctx) {
		if payload, ok := stale(); ok {
			return payload
		}
		s.log.Warn("hết hạn mức và không có đệm, trả dữ liệu mẫu", "key", cacheKey)
		return Payload[T]{Data: fallback(), Source: SourceUnavailable, CachedAt: now}
	}

	data, err := live(ctx)
	if err != nil {
		s.log.Error("gọi API-Football hỏng", "key", cacheKey, "err", err)
		if payload, ok := stale(); ok {
			return payload
		}
		return Payload[T]{Data: fallback(), Source: SourceUnavailable, CachedAt: now}
	}

	if encoded, err := json.Marshal(data); err != nil {
		s.log.Warn("không mã hoá được để ghi đệm", "key", cacheKey, "err", err)
	} else {
		expiresAt := now.Add(ttl)
		if err := s.store.Put(ctx, cacheKey, encoded, &expiresAt); err != nil {
			s.log.Warn("không ghi được đệm", "key", cacheKey, "err", err)
		}
	}

	return Payload[T]{Data: data, Source: SourceLive, CachedAt: now}
}

// ── Các endpoint ────────────────────────────────────────────────────────────

// Leagues trả về danh sách giải đấu.
func (s *Service) Leagues(ctx context.Context, country, search string) Payload[[]football.League] {
	key := cacheKey("leagues", country, search)
	return resolve(ctx, s, key, ttlMetadata,
		func(ctx context.Context) ([]football.League, error) {
			return s.apiClient().Leagues(ctx, country, search)
		},
		func() []football.League { return nil },
	)
}

// FixturesByDate trả về lịch thi đấu của một ngày, tuỳ chọn lọc theo giải.
func (s *Service) FixturesByDate(ctx context.Context, date, leagueID, season string) Payload[[]football.Match] {
	key := cacheKey("fixtures:date", date, leagueID, season)

	// Ngày trong quá khứ thì kết quả đã cố định — giữ lâu hơn hẳn. Đây là chỗ
	// tiết kiệm hạn mức nhiều nhất: người dùng lật lại lịch tuần trước không
	// tốn thêm lượt nào sau lần đầu.
	ttl := s.liveTTL
	if day, err := time.Parse("2006-01-02", date); err == nil && day.Before(s.startOfDay()) {
		ttl = ttlMetadata
	}

	return resolve(ctx, s, key, ttl,
		func(ctx context.Context) ([]football.Match, error) {
			return s.apiClient().Fixtures(ctx, football.FixtureQuery{
				Date: date, League: leagueID, Season: season,
			})
		},
		func() []football.Match { return nil },
	)
}

// TeamFixtures trả về lịch thi đấu của một đội trong một mùa.
func (s *Service) TeamFixtures(ctx context.Context, teamID, season, leagueID string) Payload[[]football.Match] {
	key := cacheKey("fixtures:team", teamID, season, leagueID)

	return resolve(ctx, s, key, s.seasonTTL(season),
		func(ctx context.Context) ([]football.Match, error) {
			return s.apiClient().Fixtures(ctx, football.FixtureQuery{
				Team: teamID, Season: season, League: leagueID,
			})
		},
		func() []football.Match { return nil },
	)
}

// LeagueFixtures trả về lịch thi đấu cả mùa của một giải.
//
// Tách khỏi FixturesByDate dù cùng gọi một endpoint của nhà cung cấp, vì hai
// bên có vòng đời khác hẳn: lịch một NGÀY phải làm mới mỗi 15 phút để tỉ số
// nhúc nhích, còn lịch cả MÙA thì gần như bất động — chỉ tỉ số của vài trận
// trong tuần là đổi. Dùng chung một TTL sẽ hoặc đốt hạn mức cho dữ liệu không
// đổi, hoặc để tỉ số hôm nay đứng im cả ngày.
func (s *Service) LeagueFixtures(ctx context.Context, leagueID, season string) Payload[[]football.Match] {
	key := cacheKey("fixtures:league", leagueID, season)

	return resolve(ctx, s, key, s.seasonTTL(season),
		func(ctx context.Context) ([]football.Match, error) {
			return s.apiClient().Fixtures(ctx, football.FixtureQuery{
				League: leagueID, Season: season,
			})
		},
		func() []football.Match { return nil },
	)
}

// seasonTTL: mùa đã khép lại thì kết quả không đổi nữa, giữ lâu như dữ liệu
// tĩnh; mùa đang chạy thì làm mới mỗi ngày.
func (s *Service) seasonTTL(season string) time.Duration {
	if year, err := strconv.Atoi(season); err == nil && year < s.now().UTC().Year() {
		return ttlMetadata
	}
	return ttlSeason
}

// Teams tìm đội theo tên hoặc theo giải.
func (s *Service) Teams(ctx context.Context, search, leagueID, season string) Payload[[]football.Team] {
	key := cacheKey("teams", search, leagueID, season)
	return resolve(ctx, s, key, ttlMetadata,
		func(ctx context.Context) ([]football.Team, error) {
			return s.apiClient().Teams(ctx, search, leagueID, season)
		},
		func() []football.Team { return nil },
	)
}

// Players tìm cầu thủ theo tên.
func (s *Service) Players(ctx context.Context, search string) Payload[[]football.Player] {
	key := cacheKey("players", search)
	return resolve(ctx, s, key, ttlMetadata,
		func(ctx context.Context) ([]football.Player, error) {
			return s.apiClient().Players(ctx, search)
		},
		func() []football.Player { return nil },
	)
}

// Player lấy hồ sơ một cầu thủ trong một mùa.
func (s *Service) Player(ctx context.Context, playerID, season string) Payload[*football.Player] {
	key := cacheKey("player", playerID, season)

	return resolve(ctx, s, key, s.seasonTTL(season),
		func(ctx context.Context) (*football.Player, error) {
			player, err := s.apiClient().Player(ctx, playerID, season)
			if err != nil {
				return nil, err
			}
			return &player, nil
		},
		func() *football.Player { return nil },
	)
}

// cacheKey ghép các phần thành một khoá đệm ổn định.
//
// Chuẩn hoá về chữ thường và cắt khoảng trắng thừa để "Arsenal", "arsenal " và
// " ARSENAL" dùng chung một ô đệm — ba lượt gọi ra ngoài cho cùng một câu hỏi
// là ba phần trăm hạn mức ngày bị vứt đi.
func cacheKey(parts ...string) string {
	cleaned := make([]string, 0, len(parts))
	for _, part := range parts {
		part = strings.ToLower(strings.TrimSpace(part))
		if part == "" {
			part = "-"
		}
		cleaned = append(cleaned, part)
	}
	return fmt.Sprintf("v1:%s", strings.Join(cleaned, ":"))
}
