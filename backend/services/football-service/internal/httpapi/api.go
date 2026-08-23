// Package httpapi là tầng vận chuyển: định tuyến, kiểm tra tham số, và đóng
// gói phản hồi. Nó không chứa quyết định nghiệp vụ nào.
package httpapi

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/KietAnhCS/coccoc-browser-like/football-service/internal/config"
	"github.com/KietAnhCS/coccoc-browser-like/football-service/internal/service"
)

// dateFormat là định dạng ngày duy nhất mà API này nhận.
const dateFormat = "2006-01-02"

var datePattern = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}$`)

// API giữ các phụ thuộc của tầng HTTP.
type API struct {
	svc *service.Service
	cfg config.Config
	log *slog.Logger
	now func() time.Time
}

// New dựng tầng HTTP.
func New(svc *service.Service, cfg config.Config, log *slog.Logger) *API {
	return &API{svc: svc, cfg: cfg, log: log, now: time.Now}
}

// Handler trả về http.Handler đã gắn đủ middleware.
func (a *API) Handler() http.Handler {
	mux := http.NewServeMux()

	// Mẫu đường dẫn có tên phương thức là cú pháp của Go 1.22 trở lên. Nhờ nó
	// mà service này không cần một thư viện router nào: một POST gửi nhầm vào
	// endpoint chỉ-đọc sẽ nhận 405 do chính thư viện chuẩn trả, chứ không lọt
	// vào handler rồi mới bị chặn bằng tay.
	mux.HandleFunc("GET /api/v1/health", a.health)
	mux.HandleFunc("GET /api/v1/status", a.status)
	mux.HandleFunc("GET /api/v1/leagues", a.leagues)
	mux.HandleFunc("GET /api/v1/leagues/{id}/fixtures", a.leagueFixtures)
	mux.HandleFunc("GET /api/v1/fixtures", a.fixtures)
	mux.HandleFunc("GET /api/v1/teams", a.teams)
	mux.HandleFunc("GET /api/v1/teams/{id}/fixtures", a.teamFixtures)
	mux.HandleFunc("GET /api/v1/players", a.players)
	mux.HandleFunc("GET /api/v1/players/{id}", a.player)
	mux.HandleFunc("PUT /api/v1/config/api-key", a.setAPIKey)
	mux.HandleFunc("GET /metrics", a.metrics)

	return a.withCORS(a.withLogging(mux))
}

// ── Middleware ──────────────────────────────────────────────────────────────

func (a *API) withCORS(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", a.cfg.CORSOrigin)
		w.Header().Set("Access-Control-Allow-Methods", "GET, PUT, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type")

		// Tiến trình dựng giao diện của Electron gửi Origin là một scheme
		// riêng, không phải http(s). Mọi endpoint ở đây đều CHỈ ĐỌC và không
		// mang danh tính người dùng, nên mặc định `*` không mở ra rủi ro nào —
		// không có phiên đăng nhập để mượn.
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (a *API) withLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := a.now()
		recorder := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(recorder, r)

		// Không ghi log cho /metrics và /health: Prometheus quét 15 giây một
		// lần và Docker kiểm tra sức khoẻ cũng vậy, nên hai đường dẫn ấy sẽ
		// chiếm hơn 99% số dòng log và chôn vùi mọi thứ đáng đọc.
		if r.URL.Path == "/metrics" || r.URL.Path == "/api/v1/health" {
			return
		}
		a.log.Info("request",
			"method", r.Method,
			"path", r.URL.Path,
			"query", r.URL.RawQuery,
			"status", recorder.status,
			"ms", a.now().Sub(started).Milliseconds(),
		)
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

// ── Phản hồi ────────────────────────────────────────────────────────────────

type meta struct {
	// CachedAt là lúc dữ liệu này được lấy từ nhà cung cấp, KHÔNG phải lúc
	// phản hồi được tạo. Giao diện dựa vào đây để nói "cập nhật lúc 20:15" —
	// và đó là cách trung thực duy nhất để hiển thị dữ liệu qua một tầng đệm
	// 15 phút mà không giả vờ nó là thời gian thực.
	CachedAt time.Time `json:"cachedAt"`
	Source   string    `json:"source"`
	Stale    bool      `json:"stale"`
}

type envelope struct {
	Data any  `json:"data"`
	Meta meta `json:"meta"`
}

func (a *API) writePayload(w http.ResponseWriter, data any, source string, cachedAt time.Time) {
	a.writeJSON(w, http.StatusOK, envelope{
		Data: data,
		Meta: meta{
			CachedAt: cachedAt,
			Source:   source,
			Stale:    source == service.SourceStale,
		},
	})
}

func (a *API) writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(body); err != nil {
		a.log.Error("ghi phản hồi hỏng", "err", err)
	}
}

func (a *API) writeError(w http.ResponseWriter, status int, code, message string) {
	a.writeJSON(w, status, map[string]any{
		"error": map[string]string{"code": code, "message": message},
	})
}

// ── Handler ─────────────────────────────────────────────────────────────────

func (a *API) health(w http.ResponseWriter, r *http.Request) {
	a.writeJSON(w, http.StatusOK, map[string]any{
		"status":     "UP",
		"sampleOnly": !a.svc.HasAPIKey(),
	})
}

func (a *API) status(w http.ResponseWriter, r *http.Request) {
	used, budget, err := a.svc.Usage(r.Context())
	if err != nil {
		a.writeError(w, http.StatusServiceUnavailable, "USAGE_UNAVAILABLE", "Không đọc được sổ hạn mức.")
		return
	}
	a.writeJSON(w, http.StatusOK, map[string]any{
		"used":       used,
		"budget":     budget,
		"remaining":  max(0, budget-used),
		"sampleOnly": !a.svc.HasAPIKey(),
		"provider":   a.svc.ProviderName(),
	})
}

func (a *API) leagues(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query()
	payload := a.svc.Leagues(r.Context(), trim(query.Get("country")), trim(query.Get("search")))
	a.writePayload(w, payload.Data, payload.Source, payload.CachedAt)
}

// leagueFixtures phục vụ lịch CẢ MÙA của một giải.
//
// Đường dẫn lồng dưới /leagues/{id} chứ không phải một bộ lọc của /fixtures,
// vì hai bên trả về hai thứ khác nhau về bản chất: /fixtures?date= là ảnh chụp
// một ngày, còn đây là toàn bộ mùa. Gộp chung thì một request quên tham số
// `date` sẽ lặng lẽ kéo về hàng trăm trận thay vì báo lỗi.
func (a *API) leagueFixtures(w http.ResponseWriter, r *http.Request) {
	season := trim(r.URL.Query().Get("season"))
	if season == "" {
		season = a.currentSeason()
	}

	payload := a.svc.LeagueFixtures(r.Context(), r.PathValue("id"), season)
	a.writePayload(w, payload.Data, payload.Source, payload.CachedAt)
}

func (a *API) fixtures(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query()

	date := trim(query.Get("date"))
	if date == "" {
		date = a.now().UTC().Format(dateFormat)
	}
	if !datePattern.MatchString(date) {
		a.writeError(w, http.StatusBadRequest, "BAD_DATE", "Tham số `date` phải có dạng YYYY-MM-DD.")
		return
	}
	if _, err := time.Parse(dateFormat, date); err != nil {
		a.writeError(w, http.StatusBadRequest, "BAD_DATE", "Ngày không tồn tại: "+date)
		return
	}

	payload := a.svc.FixturesByDate(r.Context(), date, trim(query.Get("league")), trim(query.Get("season")))
	a.writePayload(w, payload.Data, payload.Source, payload.CachedAt)
}

func (a *API) teams(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query()
	search, league := trim(query.Get("search")), trim(query.Get("league"))

	// API-Football từ chối /teams khi không có bộ lọc nào, và trả về một lỗi
	// khó hiểu. Chặn ở đây để người gọi nhận được câu giải thích thật, và để
	// không tiêu mất một lượt hạn mức cho một request chắc chắn hỏng.
	if search == "" && league == "" {
		a.writeError(w, http.StatusBadRequest, "MISSING_FILTER", "Cần ít nhất một trong hai tham số: `search` hoặc `league`.")
		return
	}

	payload := a.svc.Teams(r.Context(), search, league, trim(query.Get("season")))
	a.writePayload(w, payload.Data, payload.Source, payload.CachedAt)
}

func (a *API) teamFixtures(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query()
	season := trim(query.Get("season"))
	if season == "" {
		season = a.currentSeason()
	}

	payload := a.svc.TeamFixtures(r.Context(), r.PathValue("id"), season, trim(query.Get("league")))
	a.writePayload(w, payload.Data, payload.Source, payload.CachedAt)
}

func (a *API) players(w http.ResponseWriter, r *http.Request) {
	search := trim(r.URL.Query().Get("search"))
	if len([]rune(search)) < 3 {
		// Ngưỡng của chính nhà cung cấp: /players/profiles đòi `search` dài ít
		// nhất 3 ký tự. Chặn sớm để một lần gõ hụt không tốn hạn mức.
		a.writeError(w, http.StatusBadRequest, "SEARCH_TOO_SHORT", "Tham số `search` phải có ít nhất 3 ký tự.")
		return
	}

	payload := a.svc.Players(r.Context(), search)
	a.writePayload(w, payload.Data, payload.Source, payload.CachedAt)
}

func (a *API) player(w http.ResponseWriter, r *http.Request) {
	season := trim(r.URL.Query().Get("season"))
	if season == "" {
		season = a.currentSeason()
	}

	payload := a.svc.Player(r.Context(), r.PathValue("id"), season)
	if payload.Data == nil {
		a.writeError(w, http.StatusNotFound, "PLAYER_NOT_FOUND", "Không tìm thấy cầu thủ này ở mùa "+season+".")
		return
	}
	a.writePayload(w, payload.Data, payload.Source, payload.CachedAt)
}

// setAPIKey nhận khoá dán từ giao diện.
//
// <h3>Vì sao một endpoint GHI trong một service chỉ-đọc</h3>
//
// Vì con đường kia — sửa `.env` rồi `docker compose up` lại — đòi người dùng
// rời khỏi ứng dụng, mở đúng một tệp ẩn ở thư mục gốc, rồi gõ một câu lệnh
// Docker. Ba bước ấy là chỗ mà phần lớn người thử repo này bỏ cuộc, và kết quả
// là họ kết luận "phần bóng đá chỉ có dữ liệu giả".
//
// <h3>Vì sao PUT chứ không phải POST, và vì sao thế là đủ an toàn</h3>
//
// Service nghe ở localhost và CORS mở `*`. Một POST đơn giản với
// `Content-Type: text/plain` KHÔNG kích hoạt preflight, nên bất kỳ trang web
// nào người dùng đang mở cũng có thể bắn một request như vậy vào cổng 8090.
// PUT thì luôn phải preflight — trình duyệt hỏi OPTIONS trước, và trang lạ
// không vượt qua được bước đó nếu ta không muốn. Đây là hàng rào duy nhất cần
// thiết: thứ duy nhất ghi được là một khoá API mà kẻ tấn công phải TỰ trả tiền
// mua, và mọi endpoint khác vẫn chỉ đọc.
func (a *API) setAPIKey(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Key string `json:"key"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&body); err != nil {
		a.writeError(w, http.StatusBadRequest, "BAD_BODY", "Thân yêu cầu phải là JSON dạng {\"key\": \"...\"}.")
		return
	}

	if err := a.svc.SetAPIKey(r.Context(), body.Key); err != nil {
		// Khoá không dùng được là lỗi của DỮ LIỆU GỬI LÊN, không phải của máy
		// chủ — 400 chứ không phải 500. Kèm nguyên văn lời từ chối của nhà cung
		// cấp: "You are not subscribed to this API" nói chính xác phải làm gì
		// tiếp, còn "khoá không hợp lệ" thì không.
		a.log.Warn("khoá API bị từ chối", "err", err)
		a.writeError(w, http.StatusBadRequest, "KEY_REJECTED", err.Error())
		return
	}

	a.writeJSON(w, http.StatusOK, map[string]any{"ok": true, "sampleOnly": false})
}

func (a *API) metrics(w http.ResponseWriter, r *http.Request) {
	used, budget, err := a.svc.Usage(r.Context())
	if err != nil {
		used = -1
	}

	sampleOnly := 0
	if !a.svc.HasAPIKey() {
		sampleOnly = 1
	}

	var body strings.Builder
	writeMetric(&body, "football_api_calls_today",
		"So luot goi ra API-Football trong ngay UTC hien tai.", "gauge", used)
	writeMetric(&body, "football_api_daily_budget",
		"Han muc luot goi moi ngay.", "gauge", budget)
	writeMetric(&body, "football_sample_mode",
		"1 = dang chay bang du lieu mau vi chua co khoa API.", "gauge", sampleOnly)

	w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
	_, _ = w.Write([]byte(body.String()))
}

// writeMetric xuất một chỉ số theo định dạng phơi bày của Prometheus.
//
// Tự viết thay vì kéo client_golang về: ba chỉ số dạng gauge không đáng một
// phụ thuộc kèm hàng chục gói con, và định dạng này đã ổn định nhiều năm.
func writeMetric(out *strings.Builder, name, help, kind string, value int) {
	fmt.Fprintf(out, "# HELP %s %s\n# TYPE %s %s\n%s %d\n", name, help, name, kind, name, value)
}

// currentSeason đoán mùa giải hiện hành.
//
// API-Football đánh số mùa theo NĂM BẮT ĐẦU: mùa 2024–25 của Premier League
// mang số 2024. Các giải châu Âu khởi tranh khoảng tháng 8, nên từ tháng 7 trở
// đi phải tính sang mùa mới, còn tháng 1–6 vẫn thuộc mùa của năm trước.
func (a *API) currentSeason() string {
	now := a.now().UTC()
	year := now.Year()
	if now.Month() < time.July {
		year--
	}
	return fmt.Sprintf("%d", year)
}

func trim(value string) string { return strings.TrimSpace(value) }
