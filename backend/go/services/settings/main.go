package main

import (
	"context"
	"embed"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"github.com/go-chi/chi/v5"

	"github.com/vnsearch/backend-go/platform/auth"
	"github.com/vnsearch/backend-go/platform/config"
	"github.com/vnsearch/backend-go/platform/pg"
	"github.com/vnsearch/backend-go/platform/server"
	"github.com/vnsearch/backend-go/services/settings/internal/settings"
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
		config.Env("SETTINGS_DB_URL", "postgres://postgres:5432/vnsearch_settings"),
		config.Env("SETTINGS_DB_USER", "vnsearch_settings"),
		config.Env("SETTINGS_DB_PASSWORD", ""),
		config.Env("SETTINGS_DB_SSLMODE", "disable"),
	)

	if err := pg.Migrate(dsn, migrationsFS, "migrations"); err != nil {
		return err
	}
	pool, err := pg.Connect(ctx, dsn, config.EnvInt32("SETTINGS_DB_POOL", 3))
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

	repo := settings.NewRepo(pool)
	handler := settings.NewHandler(repo, settings.NewAudit(pool))

	r := server.New(server.Options{
		ServiceName:    "settings-service",
		AllowedOrigins: []string{config.Env("APP_CORS_ALLOWED_ORIGINS", "http://localhost:5173")},
		RateLimitRPM:   config.EnvInt("APP_SECURITY_RATE_LIMIT_RPM", 120),
		RateLimit:      config.EnvBool("APP_SECURITY_RATE_LIMIT_ENABLED", true),
		TrustProxy:     config.EnvBool("APP_SECURITY_TRUST_PROXY", true),
		HealthCheck:    repo.Ping,
	})
	r.Route("/api/settings", func(sub chi.Router) {
		sub.Use(verifier.RequireAuth)
		handler.Register(sub)
	})

	return server.Run(ctx, ":"+config.Env("SERVER_PORT", "8087"), r)
}
