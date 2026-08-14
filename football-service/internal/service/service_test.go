package service

import (
	"context"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/KietAnhCS/coccoc-browser-like/football-service/internal/config"
)

func TestCacheKeyNormalisesInput(t *testing.T) {
	// Ba cách gõ cùng một câu hỏi phải rơi vào cùng một ô đệm. Không chuẩn hoá
	// thì "Arsenal", "arsenal " và " ARSENAL" là ba lượt gọi ra ngoài — ba
	// phần trăm hạn mức ngày bị vứt đi cho cùng một câu trả lời.
	want := cacheKey("teams", "Arsenal", "")
	for _, variant := range []string{"arsenal ", " ARSENAL", "  Arsenal  "} {
		if got := cacheKey("teams", variant, ""); got != want {
			t.Errorf("cacheKey(%q) = %q, mong đợi %q", variant, got, want)
		}
	}
}

func TestCacheKeyKeepsFieldsApart(t *testing.T) {
	// Nếu phần rỗng bị bỏ đi thay vì thay bằng ký tự giữ chỗ, thì
	// ("a", "") và ("", "a") sẽ ra cùng một khoá — hai câu hỏi khác nhau dùng
	// chung một câu trả lời.
	if cacheKey("x", "a", "") == cacheKey("x", "", "a") {
		t.Error("hai bộ tham số khác nhau không được cho cùng một khoá đệm")
	}
}

func TestCacheKeyIsVersioned(t *testing.T) {
	// Tiền tố phiên bản là lối thoát khi hình dạng DTO đổi: tăng "v1" lên
	// "v2" là bỏ toàn bộ đệm cũ mà không phải xoá bảng bằng tay.
	if got := cacheKey("leagues"); got[:3] != "v1:" {
		t.Errorf("khoá đệm phải mở đầu bằng \"v1:\", nhận được %q", got)
	}
}

func TestWithoutKeyReturnsNothingAndNeverTouchesCache(t *testing.T) {
	// Chưa có khoá thì service KHÔNG bịa ra dữ liệu và cũng không chạm vào
	// đệm. Bản trước trả về một lịch thi đấu mẫu ở đây; bỏ hẳn vì một bảng tỉ
	// số hiện những trận không tồn tại tệ hơn hẳn một bảng trống nói thật.
	//
	// store nil ở đây vừa đóng vai "chưa có Postgres" vừa là cái bẫy: mọi
	// đường chạy chạm tới đệm đều sẽ panic thay vì âm thầm sai.
	cfg := config.Config{
		DatabaseURL:    "postgres://khong-dung-toi",
		APIKey:         "",
		DailyBudget:    95,
		RequestTimeout: time.Second,
	}
	svc := New(cfg, nil, slog.New(slog.NewTextHandler(io.Discard, nil)))

	payload := svc.Leagues(context.Background(), "", "")

	if payload.Source != SourceUnavailable {
		t.Errorf("Source = %q, mong đợi %q", payload.Source, SourceUnavailable)
	}
	if len(payload.Data) != 0 {
		t.Errorf("chưa có khoá thì không được trả về dữ liệu nào, nhận %d dòng", len(payload.Data))
	}
}

func TestUsageWithoutStoreReportsBudget(t *testing.T) {
	cfg := config.Config{DatabaseURL: "x", DailyBudget: 95}
	svc := New(cfg, nil, slog.New(slog.NewTextHandler(io.Discard, nil)))

	used, budget, err := svc.Usage(context.Background())
	if err != nil {
		t.Fatalf("Usage hỏng: %v", err)
	}
	if used != 0 || budget != 95 {
		t.Errorf("Usage = (%d, %d), mong đợi (0, 95)", used, budget)
	}
}

func TestStartOfDayIsUTCMidnight(t *testing.T) {
	// Hạn mức của nhà cung cấp reset theo ngày UTC. Dùng giờ địa phương ở đây
	// thì bộ đếm của ta reset lệch 7 tiếng so với của họ, và ta sẽ tưởng mình
	// còn hạn mức trong khi đã hết.
	svc := New(config.Config{DatabaseURL: "x"}, nil, slog.New(slog.NewTextHandler(io.Discard, nil)))
	svc.now = func() time.Time {
		return time.Date(2026, 8, 13, 6, 30, 0, 0, time.FixedZone("ICT", 7*3600))
	}

	start := svc.startOfDay()
	if start.Location() != time.UTC {
		t.Errorf("mốc đầu ngày phải ở UTC, nhận được %v", start.Location())
	}
	// 06:30 giờ Việt Nam ngày 13 là 23:30 UTC ngày 12 — mốc đầu ngày UTC phải
	// là 00:00 ngày 12, không phải ngày 13.
	if start.Day() != 12 {
		t.Errorf("mốc đầu ngày = %v, mong đợi 00:00 UTC ngày 12", start)
	}
}
