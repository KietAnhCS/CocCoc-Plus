package httpapi

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/KietAnhCS/coccoc-browser-like/football-service/internal/config"
	"github.com/KietAnhCS/coccoc-browser-like/football-service/internal/service"
)

// Bộ test này chạy service ở chế độ dữ liệu mẫu: không khoá API, không
// Postgres. Đó chính là hình dạng mà người vừa clone repo về sẽ gặp, nên nó
// cũng là hình dạng đáng ghim lại nhất.
func newTestAPI(t *testing.T) *API {
	t.Helper()

	cfg := config.Config{
		Port:           8090,
		DatabaseURL:    "postgres://khong-dung-toi",
		APIBaseURL:     "https://v3.football.api-sports.io",
		APIKey:         "", // rỗng ⇒ SampleOnly
		DailyBudget:    95,
		CORSOrigin:     "*",
		RequestTimeout: time.Second,
	}

	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	api := New(service.New(cfg, nil, log), cfg, log)

	// Đóng băng thời gian: "hôm nay" phải là một hằng số trong test, nếu
	// không thì bộ test sẽ hỏng vào đúng nửa đêm UTC.
	frozen := time.Date(2026, 8, 13, 20, 15, 0, 0, time.UTC)
	api.now = func() time.Time { return frozen }

	return api
}

func do(t *testing.T, api *API, target string) *httptest.ResponseRecorder {
	t.Helper()
	recorder := httptest.NewRecorder()
	api.Handler().ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, target, nil))
	return recorder
}

func TestWithoutKeyEverythingIsEmptyButHealthy(t *testing.T) {
	// Không có khoá thì không có dữ liệu — và đó là câu trả lời ĐÚNG. Bản
	// trước trả về một lịch thi đấu bịa ở đây; test này ghim lại việc nó đã bị
	// bỏ, để không ai lặng lẽ đưa dữ liệu giả trở lại.
	api := newTestAPI(t)

	for _, target := range []string{
		"/api/v1/fixtures",
		"/api/v1/leagues",
		"/api/v1/leagues/39/fixtures",
		"/api/v1/teams?league=39",
		"/api/v1/players?search=salah",
	} {
		response := do(t, api, target)
		if response.Code != http.StatusOK {
			t.Errorf("%s cho mã %d, mong đợi 200", target, response.Code)
			continue
		}

		var body struct {
			Data []json.RawMessage `json:"data"`
			Meta struct {
				Source string `json:"source"`
			} `json:"meta"`
		}
		if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
			t.Errorf("%s: giải mã hỏng: %v", target, err)
			continue
		}
		if len(body.Data) != 0 {
			t.Errorf("%s trả về %d dòng dù chưa có khoá", target, len(body.Data))
		}
		if body.Meta.Source != service.SourceUnavailable {
			t.Errorf("%s: source = %q, mong đợi %q", target, body.Meta.Source, service.SourceUnavailable)
		}
	}
}

func TestFixturesRejectsBadDate(t *testing.T) {
	api := newTestAPI(t)

	for _, date := range []string{"13-08-2026", "2026/08/13", "hom-nay", "2026-13-45"} {
		response := do(t, api, "/api/v1/fixtures?date="+date)
		if response.Code != http.StatusBadRequest {
			t.Errorf("date=%q cho mã %d, mong đợi 400", date, response.Code)
		}
	}
}

func TestTeamsRequiresAFilter(t *testing.T) {
	// Không có bộ lọc thì API-Football chắc chắn từ chối. Chặn ở đây để không
	// tiêu mất một lượt hạn mức cho một request đằng nào cũng hỏng.
	api := newTestAPI(t)
	response := do(t, api, "/api/v1/teams")

	if response.Code != http.StatusBadRequest {
		t.Errorf("mã trạng thái = %d, mong đợi 400", response.Code)
	}
}

func TestPlayersRejectsShortSearch(t *testing.T) {
	api := newTestAPI(t)

	if got := do(t, api, "/api/v1/players?search=me").Code; got != http.StatusBadRequest {
		t.Errorf("2 ký tự cho mã %d, mong đợi 400", got)
	}
	if got := do(t, api, "/api/v1/players?search=mes").Code; got == http.StatusBadRequest {
		t.Error("3 ký tự là đủ, không được từ chối")
	}
}

func TestHealthAndStatus(t *testing.T) {
	api := newTestAPI(t)

	health := do(t, api, "/api/v1/health")
	if health.Code != http.StatusOK {
		t.Fatalf("health trả %d", health.Code)
	}

	var healthBody struct {
		Status     string `json:"status"`
		SampleOnly bool   `json:"sampleOnly"`
	}
	if err := json.Unmarshal(health.Body.Bytes(), &healthBody); err != nil {
		t.Fatalf("giải mã health hỏng: %v", err)
	}
	if healthBody.Status != "UP" || !healthBody.SampleOnly {
		t.Errorf("health = %+v, mong đợi UP và sampleOnly=true", healthBody)
	}

	status := do(t, api, "/api/v1/status")
	if status.Code != http.StatusOK {
		t.Fatalf("status trả %d", status.Code)
	}
}

func TestMetricsExposesPrometheusFormat(t *testing.T) {
	api := newTestAPI(t)
	response := do(t, api, "/metrics")

	if response.Code != http.StatusOK {
		t.Fatalf("mã trạng thái = %d", response.Code)
	}
	body := response.Body.String()
	for _, needle := range []string{
		"# TYPE football_api_calls_today gauge",
		"football_api_daily_budget 95",
		"football_sample_mode 1",
	} {
		if !strings.Contains(body, needle) {
			t.Errorf("thiếu dòng %q trong:\n%s", needle, body)
		}
	}
}

func TestWriteMethodsAreRejected(t *testing.T) {
	// Mọi endpoint ở đây đều chỉ đọc. Mẫu định tuyến "GET /..." của Go 1.22
	// lo việc này, và test ghim lại điều đó để ai đó đổi sang mẫu không có
	// phương thức sẽ thấy ngay.
	api := newTestAPI(t)
	recorder := httptest.NewRecorder()
	api.Handler().ServeHTTP(recorder, httptest.NewRequest(http.MethodPost, "/api/v1/fixtures", nil))

	if recorder.Code != http.StatusMethodNotAllowed {
		t.Errorf("POST cho mã %d, mong đợi 405", recorder.Code)
	}
}

func TestCurrentSeasonUsesStartYear(t *testing.T) {
	// API-Football đánh số mùa theo NĂM BẮT ĐẦU: mùa 2024–25 mang số 2024.
	api := newTestAPI(t)

	api.now = func() time.Time { return time.Date(2026, 8, 13, 0, 0, 0, 0, time.UTC) }
	if got := api.currentSeason(); got != "2026" {
		t.Errorf("tháng 8/2026 thuộc mùa 2026, nhận được %q", got)
	}

	api.now = func() time.Time { return time.Date(2026, 3, 1, 0, 0, 0, 0, time.UTC) }
	if got := api.currentSeason(); got != "2025" {
		t.Errorf("tháng 3/2026 vẫn thuộc mùa 2025, nhận được %q", got)
	}
}
