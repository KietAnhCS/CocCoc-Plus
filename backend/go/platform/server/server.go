package server

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	chimw "github.com/go-chi/chi/v5/middleware"
	"github.com/prometheus/client_golang/prometheus/promhttp"

	"github.com/vnsearch/backend-go/platform/httpx"
	"github.com/vnsearch/backend-go/platform/middleware"
)

type Options struct {
	ServiceName    string
	AllowedOrigins []string
	RateLimitRPM   int
	RateLimit      bool
	TrustProxy     bool
	HealthCheck    func(ctx context.Context) error
}

func New(opts Options) *chi.Mux {
	r := chi.NewRouter()
	// KHÔNG dùng chimw.RealIP: nó ghi đè r.RemoteAddr từ X-Forwarded-For một
	// cách VÔ ĐIỀU KIỆN, vô hiệu hoá cờ trust-proxy của bộ giới hạn tần suất.
	// middleware.RateLimiter tự tách địa chỉ theo cờ đó.
	r.Use(requestLogger)
	r.Use(httpx.Recoverer)
	r.Use(middleware.SecurityHeaders)
	r.Use(middleware.CORS(middleware.CORSOptions{AllowedOrigins: opts.AllowedOrigins}))

	rl := middleware.NewRateLimiter(opts.RateLimitRPM, opts.RateLimit, opts.TrustProxy)
	r.Use(middleware.PathScoped("/api/", rl.Handler))

	r.Handle("/actuator/prometheus", promhttp.Handler())
	r.Get("/actuator/health", func(w http.ResponseWriter, req *http.Request) {
		if opts.HealthCheck != nil {
			ctx, cancel := context.WithTimeout(req.Context(), 3*time.Second)
			defer cancel()
			if err := opts.HealthCheck(ctx); err != nil {
				httpx.WriteJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "DOWN"})
				return
			}
		}
		httpx.WriteJSON(w, http.StatusOK, map[string]string{"status": "UP"})
	})

	return r
}

func requestLogger(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		ww := chimw.NewWrapResponseWriter(w, r.ProtoMajor)
		next.ServeHTTP(ww, r)
		if r.URL.Path == "/actuator/health" || r.URL.Path == "/actuator/prometheus" {
			return
		}
		slog.Info("request",
			"method", r.Method, "path", r.URL.Path,
			"status", ww.Status(), "bytes", ww.BytesWritten(),
			"ms", time.Since(start).Milliseconds())
	})
}

func Run(ctx context.Context, addr string, handler http.Handler) error {
	srv := &http.Server{
		Addr:              addr,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
	}
	errCh := make(chan error, 1)
	go func() {
		slog.Info("listening", "addr", addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
	}()

	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		slog.Info("shutting down")
		return srv.Shutdown(shutdownCtx)
	}
}
