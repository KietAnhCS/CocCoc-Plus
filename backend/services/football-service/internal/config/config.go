// Package config đọc toàn bộ cấu hình của service từ biến môi trường.
//
// Không có tệp cấu hình nào: service này chạy trong container, và một tiến
// trình chạy trong container thì biến môi trường là kênh cấu hình DUY NHẤT mà
// cả Docker Compose lẫn Kubernetes đều nói được. Thêm một tệp YAML nữa chỉ tạo
// ra một nguồn sự thật thứ hai để hai bên lệch nhau.
package config

import (
	"fmt"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config là cấu hình đã được kiểm tra hợp lệ của một tiến trình.
type Config struct {
	// Port là cổng HTTP. Mặc định 8090 — tránh 8080 của backend Java và 3000
	// của các công cụ frontend.
	Port int

	// DatabaseURL trỏ tới đúng CSDL mà backend chính đang dùng. Service này
	// KHÔNG dựng CSDL riêng: nó tạo một schema `football` bên trong đó (xem
	// gói store). Một cụm Postgres cho một đồ án là đủ; hai cụm chỉ tạo thêm
	// một thứ nữa để sao lưu và để quên.
	//
	// Giá trị này có thể đặt thẳng bằng FOOTBALL_DATABASE_URL, hoặc ghép từ
	// các phần rời (HOST/PORT/USER/PASSWORD/NAME). Xem buildDatabaseURL để
	// biết vì sao cách ghép từ phần rời mới là cách nên dùng.
	DatabaseURL string

	// APIBaseURL và APIKey trỏ tới API-Football.
	//
	// APIKey ĐƯỢC PHÉP rỗng, và đó là một lựa chọn có chủ ý: khi rỗng, service
	// chạy bằng dữ liệu mẫu nhúng sẵn thay vì từ chối khởi động. Xem
	// SampleOnly.
	APIBaseURL string
	APIKey     string

	// DailyBudget là số lượt gọi ra ngoài tối đa trong một ngày UTC.
	//
	// Gói miễn phí của API-Football cho 100 lượt/ngày. Mặc định 95 chừa lại 5
	// lượt đệm: hạn mức đếm ở phía họ, còn ta đếm ở phía mình, và hai bộ đếm
	// không bao giờ khớp tuyệt đối (một request lỗi mạng có thể đã tới nơi).
	// Đụng trần của họ thì bị khoá cả ngày; đụng trần của mình thì chỉ là
	// service trả dữ liệu cũ.
	DailyBudget int

	// CORSOrigin là danh sách origin được phép, ngăn cách bởi dấu phẩy.
	// Mặc định `*` vì mọi endpoint của service này đều CHỈ ĐỌC và không mang
	// danh tính người dùng — không có gì để CSRF.
	CORSOrigin string

	// RequestTimeout là hạn chờ cho một lượt gọi ra API-Football.
	RequestTimeout time.Duration
}

// SampleOnly cho biết service đang chạy bằng dữ liệu mẫu nhúng sẵn.
//
// Vì sao không "hỏng to" như ADMIN_API_KEY của backend chính: hai khoá đó bảo
// vệ hai thứ khác hẳn nhau. Thiếu ADMIN_API_KEY nghĩa là mở toang một endpoint
// khiến máy chủ đi tải URL do người lạ chọn — một lỗ hổng. Thiếu
// API_FOOTBALL_KEY chỉ nghĩa là không có tỉ số thật; không ai bị hại, và người
// vừa clone repo về vẫn cần thấy giao diện chạy được.
func (c Config) SampleOnly() bool { return c.APIKey == "" }

// Load đọc và kiểm tra cấu hình. Trả lỗi thay vì gọi os.Exit để hàm này còn
// kiểm thử được.
func Load() (Config, error) {
	cfg := Config{
		Port:           envInt("FOOTBALL_PORT", 8090),
		DatabaseURL:    buildDatabaseURL(),
		APIBaseURL:     envString("FOOTBALL_API_BASE_URL", "https://v3.football.api-sports.io"),
		APIKey:         strings.TrimSpace(envString("FOOTBALL_API_KEY", "")),
		DailyBudget:    envInt("FOOTBALL_DAILY_BUDGET", 95),
		CORSOrigin:     envString("FOOTBALL_CORS_ORIGIN", "*"),
		RequestTimeout: time.Duration(envInt("FOOTBALL_REQUEST_TIMEOUT_SECONDS", 10)) * time.Second,
	}

	if cfg.DatabaseURL == "" {
		return Config{}, fmt.Errorf("thiếu cấu hình CSDL: đặt FOOTBALL_DATABASE_URL, hoặc bộ FOOTBALL_DB_HOST/USER/PASSWORD/NAME")
	}
	if cfg.Port < 1 || cfg.Port > 65535 {
		return Config{}, fmt.Errorf("FOOTBALL_PORT không hợp lệ: %d", cfg.Port)
	}
	if cfg.DailyBudget < 0 {
		return Config{}, fmt.Errorf("FOOTBALL_DAILY_BUDGET không được âm: %d", cfg.DailyBudget)
	}

	return cfg, nil
}

// buildDatabaseURL dựng chuỗi kết nối Postgres.
//
// <h3>Vì sao không ghép chuỗi URL ở tệp docker-compose</h3>
//
// Bản đầu của service này nhận nguyên một URL đã ghép sẵn trong compose:
//
//	postgres://vnsearch:${POSTGRES_PASSWORD}@postgres:5432/vnsearch
//
// Nó chạy được cho tới khi gặp một mật khẩu thật. Mật khẩu sinh ngẫu nhiên rất
// hay chứa `@`, `:`, `/`, `#` hay `?` — đúng những ký tự phân tách của cú pháp
// URL. Một dấu `@` trong mật khẩu cắt URL sai chỗ, và lỗi hiện ra là
// "password authentication failed", tức là trỏ người đọc đi tìm sai hướng
// hoàn toàn: mật khẩu ĐÚNG, chỉ có chuỗi kết nối là hỏng.
//
// Nhận từng phần rời rồi để url.UserPassword tự mã hoá thì cả lớp lỗi này
// biến mất, và tệp compose không phải biết luật thoát ký tự của URL.
//
// FOOTBALL_DATABASE_URL vẫn được ưu tiên khi có, cho những nơi chuỗi kết nối
// đến từ một Secret dựng sẵn — Kubernetes chẳng hạn.
func buildDatabaseURL() string {
	if direct := envString("FOOTBALL_DATABASE_URL", ""); direct != "" {
		return direct
	}

	host := envString("FOOTBALL_DB_HOST", "")
	if host == "" {
		return ""
	}

	query := url.Values{}
	// `disable` cho mặc định vì đường chạy chính là hai container nói chuyện
	// trong cùng một mạng Docker, nơi Postgres không bật TLS. Để mặc định
	// `prefer` thì mỗi lần kết nối đều tốn một vòng bắt tay hỏng và một dòng
	// "server refused TLS connection" trong log — nhiễu, và che mất lỗi thật.
	query.Set("sslmode", envString("FOOTBALL_DB_SSLMODE", "disable"))

	dsn := url.URL{
		Scheme:   "postgres",
		User:     url.UserPassword(envString("FOOTBALL_DB_USER", "vnsearch"), envString("FOOTBALL_DB_PASSWORD", "")),
		Host:     host + ":" + envString("FOOTBALL_DB_PORT", "5432"),
		Path:     "/" + envString("FOOTBALL_DB_NAME", "vnsearch"),
		RawQuery: query.Encode(),
	}
	return dsn.String()
}

func envString(key, fallback string) string {
	if value, ok := os.LookupEnv(key); ok && value != "" {
		return value
	}
	return fallback
}

func envInt(key string, fallback int) int {
	raw, ok := os.LookupEnv(key)
	if !ok || raw == "" {
		return fallback
	}
	value, err := strconv.Atoi(raw)
	if err != nil {
		return fallback
	}
	return value
}
