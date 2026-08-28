package main

import (
	"context"
	"embed"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/go-chi/chi/v5"

	"github.com/vnsearch/backend-go/platform/auth"
	"github.com/vnsearch/backend-go/platform/config"
	"github.com/vnsearch/backend-go/platform/pg"
	"github.com/vnsearch/backend-go/platform/server"
	"github.com/vnsearch/backend-go/services/downloads/internal/downloads"
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

	dsn := pg.DSN(
		config.Env("DOWNLOADS_DB_URL", "postgres://postgres:5432/vnsearch_downloads"),
		config.Env("DOWNLOADS_DB_USER", "vnsearch_downloads"),
		config.Env("DOWNLOADS_DB_PASSWORD", ""),
		config.Env("DOWNLOADS_DB_SSLMODE", "disable"),
	)

	if err := pg.Migrate(dsn, migrationsFS, "migrations"); err != nil {
		return err
	}
	pool, err := pg.Connect(ctx, dsn, config.EnvInt32("DOWNLOADS_DB_POOL", 5))
	if err != nil {
		return err
	}
	defer pool.Close()

	verifier, err := auth.NewVerifier(ctx, auth.Config{
		JWKSURL:  config.Env("AUTH_JWKS_URI", "http://auth-service:8081/oauth2/jwks"),
		Issuer:   config.Env("AUTH_ISSUER_URI", "http://auth-service:8081"),
		Audience: config.Env("AUTH_AUDIENCE", "vnsearch-api"),
	})
	if err != nil {
		return err
	}

	repo := downloads.NewRepo(pool)
	svc := downloads.NewService(repo, func() time.Time { return time.Now().UTC() })
	handler := downloads.NewHandler(svc, downloads.NewAudit(pool))

	r := server.New(server.Options{
		ServiceName:    "downloads-service",
		AllowedOrigins: []string{config.Env("APP_CORS_ALLOWED_ORIGINS", "http://localhost:5173")},
		RateLimitRPM:   config.EnvInt("APP_SECURITY_RATE_LIMIT_RPM", 120),
		RateLimit:      config.EnvBool("APP_SECURITY_RATE_LIMIT_ENABLED", true),
		TrustProxy:     config.EnvBool("APP_SECURITY_TRUST_PROXY", true),
		HealthCheck:    repo.Ping,
	})
	r.Route("/api/downloads", func(sub chi.Router) {
		sub.Use(verifier.RequireAuth)
		handler.Register(sub)
	})

	return server.Run(ctx, ":"+config.Env("SERVER_PORT", "8086"), r)
}
