package main

import (
	"context"
	"embed"
	"log/slog"
	"net/url"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/prometheus/client_golang/prometheus"

	"github.com/vnsearch/backend-go/platform/config"
	"github.com/vnsearch/backend-go/platform/pg"
	"github.com/vnsearch/backend-go/platform/server"
	"github.com/vnsearch/backend-go/services/football/internal/football"
)

//go:embed migrations/*.sql
var migrationsFS embed.FS

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})))

	if err := run(); err != nil {
		slog.Error("fatal", "err", err)
		os.Exit(1)
	}
}

func run() error {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	props := football.Properties{
		APIBaseURL:     config.Env("FOOTBALL_API_BASE_URL", "https://v3.football.api-sports.io"),
		APIKey:         config.Env("FOOTBALL_API_KEY", ""),
		DailyBudget:    config.EnvInt("FOOTBALL_DAILY_BUDGET", 95),
		RequestTimeout: config.EnvDuration("FOOTBALL_REQUEST_TIMEOUT", 10*time.Second),
		LiveTTL:        config.EnvDuration("FOOTBALL_LIVE_TTL", 15*time.Minute),
		SeasonTTL:      config.EnvDuration("FOOTBALL_SEASON_TTL", 24*time.Hour),
		MetadataTTL:    config.EnvDuration("FOOTBALL_METADATA_TTL", 7*24*time.Hour),
	}

	dsn := databaseDSN()

	if err := pg.Migrate(dsn, migrationsFS, "migrations"); err != nil {
		return err
	}

	pool, err := pg.Connect(ctx, dsn, config.EnvInt32("FOOTBALL_DB_POOL", 5))
	if err != nil {
		return err
	}
	defer pool.Close()

	store := football.NewPGStore(pool)
	clock := func() time.Time { return time.Now().UTC() }
	svc := football.NewService(props, store, clock)
	svc.RestoreAPIKey(ctx)

	registerMetrics(svc)

	r := server.New(server.Options{
		ServiceName:    "football-service",
		AllowedOrigins: []string{config.Env("APP_CORS_ALLOWED_ORIGINS", "http://localhost:5173")},
		RateLimitRPM:   config.EnvInt("APP_SECURITY_RATE_LIMIT_RPM", 120),
		RateLimit:      config.EnvBool("APP_SECURITY_RATE_LIMIT_ENABLED", true),
		TrustProxy:     config.EnvBool("APP_SECURITY_TRUST_PROXY", true),
		HealthCheck: func(ctx context.Context) error {
			return store.Ping(ctx)
		},
	})

	handler := football.NewHandler(svc, clock)
	r.Mount("/api/v1", handler.Routes())

	addr := ":" + config.Env("SERVER_PORT", "8090")
	return server.Run(ctx, addr, r)
}

func databaseDSN() string {
	if raw := config.Env("FOOTBALL_DB_URL", ""); raw != "" {
		return raw
	}
	host := config.Env("FOOTBALL_DB_HOST", "localhost")
	port := config.Env("FOOTBALL_DB_PORT", "5432")
	name := config.Env("FOOTBALL_DB_NAME", "vnsearch")
	user := config.Env("FOOTBALL_DB_USER", "vnsearch")
	pass := config.Env("FOOTBALL_DB_PASSWORD", "vnsearch")

	u := url.URL{
		Scheme: "postgres",
		User:   url.UserPassword(user, pass),
		Host:   host + ":" + port,
		Path:   "/" + name,
	}
	q := u.Query()
	q.Set("sslmode", config.Env("FOOTBALL_DB_SSLMODE", "disable"))
	u.RawQuery = q.Encode()
	return u.String()
}

func registerMetrics(svc *football.Service) {
	prometheus.MustRegister(prometheus.NewGaugeFunc(
		prometheus.GaugeOpts{Name: "football_api_calls_today", Help: "API-Football calls since UTC midnight."},
		func() float64 {
			used, err := svc.Used(context.Background())
			if err != nil {
				return -1
			}
			return float64(used)
		}))
	prometheus.MustRegister(prometheus.NewGaugeFunc(
		prometheus.GaugeOpts{Name: "football_api_daily_budget", Help: "Daily API call budget."},
		func() float64 { return float64(svc.Budget()) }))
	prometheus.MustRegister(prometheus.NewGaugeFunc(
		prometheus.GaugeOpts{Name: "football_sample_mode", Help: "1 when running without an API key."},
		func() float64 {
			if svc.HasAPIKey() {
				return 0
			}
			return 1
		}))
}
