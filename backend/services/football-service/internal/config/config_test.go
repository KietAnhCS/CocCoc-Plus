package config

import (
	"net/url"
	"testing"
)

func TestLoadRequiresDatabaseConfig(t *testing.T) {
	t.Setenv("FOOTBALL_DATABASE_URL", "")
	t.Setenv("FOOTBALL_DB_HOST", "")

	if _, err := Load(); err == nil {
		t.Fatal("không có cả URL lẫn DB_HOST thì Load phải báo lỗi")
	}
}

func TestBuildDatabaseURLEscapesPassword(t *testing.T) {
	// Đây là lỗi đã gặp thật khi chạy compose lần đầu: mật khẩu chứa `@` cắt
	// URL sai chỗ, và Postgres trả về "password authentication failed" — một
	// thông báo trỏ đi sai hướng hoàn toàn, vì mật khẩu vốn đúng.
	t.Setenv("FOOTBALL_DATABASE_URL", "")
	t.Setenv("FOOTBALL_DB_HOST", "postgres")
	t.Setenv("FOOTBALL_DB_USER", "vnsearch")
	t.Setenv("FOOTBALL_DB_PASSWORD", "p@ss:w/rd#1?x")
	t.Setenv("FOOTBALL_DB_NAME", "vnsearch")

	raw := buildDatabaseURL()

	parsed, err := url.Parse(raw)
	if err != nil {
		t.Fatalf("chuỗi kết nối không phân tích được: %v", err)
	}
	if parsed.Host != "postgres:5432" {
		t.Errorf("Host = %q, mong đợi \"postgres:5432\" — dấu @ trong mật khẩu đã cắt sai chỗ", parsed.Host)
	}
	password, _ := parsed.User.Password()
	if password != "p@ss:w/rd#1?x" {
		t.Errorf("mật khẩu sau khi phân tích lại = %q, mong đợi nguyên văn", password)
	}
	if parsed.Path != "/vnsearch" {
		t.Errorf("tên CSDL = %q, mong đợi \"/vnsearch\"", parsed.Path)
	}
}

func TestDirectURLWinsOverParts(t *testing.T) {
	// Kubernetes thường cấp sẵn nguyên chuỗi kết nối trong một Secret; khi có
	// nó thì các phần rời phải bị bỏ qua chứ không trộn lẫn.
	t.Setenv("FOOTBALL_DATABASE_URL", "postgres://a:b@somewhere:5432/db")
	t.Setenv("FOOTBALL_DB_HOST", "postgres")

	if got := buildDatabaseURL(); got != "postgres://a:b@somewhere:5432/db" {
		t.Errorf("URL đặt thẳng phải thắng, nhận được %q", got)
	}
}

func TestLoadDefaults(t *testing.T) {
	t.Setenv("FOOTBALL_DATABASE_URL", "postgres://u:p@localhost:5432/db")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load hỏng: %v", err)
	}

	if cfg.Port != 8090 {
		t.Errorf("Port = %d, mặc định phải là 8090 để không đụng 8080 của backend Java", cfg.Port)
	}
	if cfg.DailyBudget != 95 {
		t.Errorf("DailyBudget = %d, mong đợi 95", cfg.DailyBudget)
	}
	if !cfg.SampleOnly() {
		t.Error("không đặt khoá API thì phải là chế độ dữ liệu mẫu")
	}
}

func TestSampleOnlyIgnoresBlankKey(t *testing.T) {
	t.Setenv("FOOTBALL_DATABASE_URL", "postgres://u:p@localhost:5432/db")
	// Một khoá toàn khoảng trắng là lỗi sao chép dán, không phải một khoá.
	// Coi nó là "có khoá" sẽ khiến service gọi ra ngoài và nhận 403 ở mọi
	// request, thay vì lùi về dữ liệu mẫu.
	t.Setenv("FOOTBALL_API_KEY", "   ")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load hỏng: %v", err)
	}
	if !cfg.SampleOnly() {
		t.Error("khoá toàn khoảng trắng phải được coi như không có khoá")
	}
}

func TestLoadRejectsBadPort(t *testing.T) {
	t.Setenv("FOOTBALL_DATABASE_URL", "postgres://u:p@localhost:5432/db")
	t.Setenv("FOOTBALL_PORT", "70000")

	if _, err := Load(); err == nil {
		t.Fatal("cổng ngoài dải 1–65535 phải bị từ chối")
	}
}

func TestEnvIntFallsBackOnGarbage(t *testing.T) {
	t.Setenv("FOOTBALL_DATABASE_URL", "postgres://u:p@localhost:5432/db")
	// Giá trị rác thì lùi về mặc định chứ không làm service chết: một biến
	// môi trường gõ nhầm không đáng để cả service không khởi động được.
	t.Setenv("FOOTBALL_DAILY_BUDGET", "chin-muoi-lam")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load hỏng: %v", err)
	}
	if cfg.DailyBudget != 95 {
		t.Errorf("DailyBudget = %d, giá trị rác phải lùi về 95", cfg.DailyBudget)
	}
}
