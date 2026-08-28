package main

import (
	"context"
	"log/slog"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/go-chi/chi/v5"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"

	"github.com/vnsearch/backend-go/platform/auth"
	"github.com/vnsearch/backend-go/platform/config"
	"github.com/vnsearch/backend-go/platform/server"
	"github.com/vnsearch/backend-go/services/history/internal/history"
)

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

	uri := config.Env("MONGO_URI", "mongodb://mongo:27017/vnsearch_history")

	connectCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	client, err := mongo.Connect(connectCtx, options.Client().ApplyURI(uri))
	if err != nil {
		return err
	}
	defer func() {
		disconnectCtx, c := context.WithTimeout(context.Background(), 5*time.Second)
		defer c()
		_ = client.Disconnect(disconnectCtx)
	}()
	if err := client.Ping(connectCtx, nil); err != nil {
		return err
	}

	db := client.Database(databaseName(uri))
	store := history.NewStore(db)
	if err := store.EnsureIndexes(ctx); err != nil {
		return err
	}

	verifier, err := auth.NewVerifier(ctx, auth.Config{
		JWKSURL:  config.Env("AUTH_JWKS_URI", "http://auth-service:8081/oauth2/jwks"),
		Issuer:   config.Env("AUTH_ISSUER_URI", "http://auth-service:8081"),
		Audience: config.Env("AUTH_AUDIENCE", "vnsearch-api"),
	})
	if err != nil {
		return err
	}

	handler := history.NewHandler(history.NewService(store, func() time.Time { return time.Now().UTC() }))

	r := server.New(server.Options{
		ServiceName:    "history-service",
		AllowedOrigins: []string{config.Env("APP_CORS_ALLOWED_ORIGINS", "http://localhost:5173")},
		RateLimitRPM:   config.EnvInt("APP_SECURITY_RATE_LIMIT_RPM", 120),
		RateLimit:      config.EnvBool("APP_SECURITY_RATE_LIMIT_ENABLED", true),
		TrustProxy:     config.EnvBool("APP_SECURITY_TRUST_PROXY", true),
		HealthCheck:    store.Ping,
	})
	r.Route("/api/history", func(sub chi.Router) {
		sub.Use(verifier.RequireAuth)
		handler.Register(sub)
	})

	return server.Run(ctx, ":"+config.Env("SERVER_PORT", "8085"), r)
}

func databaseName(uri string) string {
	rest := uri
	if i := strings.Index(rest, "://"); i >= 0 {
		rest = rest[i+3:]
	}
	if i := strings.IndexByte(rest, '/'); i >= 0 {
		rest = rest[i+1:]
	} else {
		return "vnsearch_history"
	}
	if i := strings.IndexAny(rest, "?"); i >= 0 {
		rest = rest[:i]
	}
	if rest == "" {
		return "vnsearch_history"
	}
	return rest
}
