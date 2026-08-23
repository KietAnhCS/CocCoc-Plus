// Lệnh server là điểm khởi động của football-service.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/KietAnhCS/coccoc-browser-like/football-service/internal/config"
	"github.com/KietAnhCS/coccoc-browser-like/football-service/internal/httpapi"
	"github.com/KietAnhCS/coccoc-browser-like/football-service/internal/service"
	"github.com/KietAnhCS/coccoc-browser-like/football-service/internal/store"
)

func main() {
	// Chế độ tự kiểm tra sức khoẻ.
	//
	// Ảnh chạy là distroless nên trong container KHÔNG có shell, không có
	// wget, không có curl — đó chính là điều khiến nó nhỏ và ít lỗ hổng. Nhưng
	// HEALTHCHECK của Docker lại cần một câu lệnh chạy được bên trong. Cách
	// thoát duy nhất mà không phải đánh đổi: chính tệp nhị phân này biết tự
	// gọi mình.
	if len(os.Args) > 1 && os.Args[1] == "--health-check" {
		os.Exit(healthCheck())
	}

	// Log JSON ngay từ đầu, không cấu hình gì thêm. Backend Java trong repo
	// phải bật profile `prod` mới chuyển sang JSON, và hệ quả là suốt một thời
	// gian dài đường chạy thật lại xuất log văn xuôi có mã màu ANSI. Ở đây chỉ
	// có một định dạng nên không có nhánh nào để quên bật.
	log := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))

	if err := run(log); err != nil {
		log.Error("service dừng vì lỗi", "err", err)
		os.Exit(1)
	}
}

// healthCheck gọi endpoint sức khoẻ của chính tiến trình đang chạy trong cùng
// container. Trả 0 khi khoẻ, 1 khi không.
func healthCheck() int {
	port := os.Getenv("FOOTBALL_PORT")
	if port == "" {
		port = "8090"
	}

	client := &http.Client{Timeout: 3 * time.Second}
	response, err := client.Get("http://127.0.0.1:" + port + "/api/v1/health")
	if err != nil {
		fmt.Fprintln(os.Stderr, "health check hỏng:", err)
		return 1
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		fmt.Fprintln(os.Stderr, "health check trả mã", response.StatusCode)
		return 1
	}
	return 0
}

func run(log *slog.Logger) error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	db, err := openStore(ctx, cfg, log)
	if err != nil {
		return err
	}
	if db != nil {
		defer db.Close()
	}

	if cfg.SampleOnly() {
		log.Warn("chưa có FOOTBALL_API_KEY — service chạy bằng dữ liệu mẫu",
			"gợi_ý", "lấy khoá miễn phí ở https://www.api-football.com/ rồi đặt vào .env")
	}

	svc := service.New(cfg, db, log)

	// Khoá dán từ giao diện ở lần chạy trước nằm trong CSDL, không nằm trong
	// biến môi trường — nạp lại ở đây để khởi động lại container không làm mất
	// nó. Không có CSDL hay đã có khoá trong .env thì lời gọi này không làm gì.
	svc.RestoreAPIKey(ctx)

	api := httpapi.New(svc, cfg, log)

	server := &http.Server{
		Addr:    fmt.Sprintf(":%d", cfg.Port),
		Handler: api.Handler(),
		// Hạn chờ đọc/ghi ở tầng máy chủ. Không có chúng thì một kết nối treo
		// giữ mãi một goroutine, và đủ nhiều kết nối như vậy là hết bộ nhớ —
		// dạng từ chối dịch vụ không cần tới lưu lượng lớn.
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	errs := make(chan error, 1)
	go func() {
		log.Info("football-service đang lắng nghe", "port", cfg.Port, "sampleOnly", cfg.SampleOnly())
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errs <- err
		}
	}()

	select {
	case err := <-errs:
		return err
	case <-ctx.Done():
		log.Info("nhận tín hiệu dừng, đóng máy chủ")
	}

	// Cho các request đang dở chạy nốt. Không có bước này thì mỗi lần triển
	// khai lại là một nhúm lỗi mạng ở phía trình duyệt.
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return server.Shutdown(shutdownCtx)
}

// openStore mở kết nối Postgres và tạo schema.
//
// Quy tắc khi không kết nối được, và lý do của nó:
//
//   - Có khoá API  → DỪNG HẲN. Bộ đệm chính là thứ giữ cho hạn mức 100
//     lượt/ngày không bị đốt sạch trong vài phút. Chạy tiếp mà không có nó
//     nghĩa là mỗi lần làm mới bảng bên là một lượt gọi ra ngoài, và tới trưa
//     thì khoá bị nhà cung cấp khoá lại. Hỏng to hơn hỏng âm thầm.
//   - Không có khoá → CHẠY TIẾP với store nil. Dữ liệu mẫu không cần đệm và
//     cũng không tiêu hạn mức nào, nên bắt người xem giao diện phải dựng
//     Postgres trước là đòi hỏi vô cớ.
func openStore(ctx context.Context, cfg config.Config, log *slog.Logger) (*store.Store, error) {
	const attempts = 10

	var lastErr error
	for attempt := 1; attempt <= attempts; attempt++ {
		db, err := store.Open(ctx, cfg.DatabaseURL)
		if err == nil {
			if err := db.Migrate(ctx); err != nil {
				db.Close()
				return nil, err
			}
			log.Info("đã kết nối Postgres và tạo schema football")
			return db, nil
		}

		lastErr = err
		log.Warn("chưa kết nối được Postgres, thử lại", "lần", attempt, "err", err)

		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(2 * time.Second):
		}
	}

	if cfg.SampleOnly() {
		log.Warn("bỏ qua Postgres và chạy bằng dữ liệu mẫu", "err", lastErr)
		return nil, nil
	}
	return nil, fmt.Errorf("không kết nối được Postgres sau %d lần thử: %w", attempts, lastErr)
}
