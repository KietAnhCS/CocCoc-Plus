//go:build integration

// Package itest spins up throwaway Postgres / MongoDB containers for
// integration tests. Compiled only under the `integration` build tag, so the
// default `go test ./...` never pulls in dockertest or needs a Docker daemon.
package itest

import (
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/ory/dockertest/v3"
	"github.com/ory/dockertest/v3/docker"
	"go.mongodb.org/mongo-driver/mongo"
	mongoopts "go.mongodb.org/mongo-driver/mongo/options"
)

func pool(t *testing.T) *dockertest.Pool {
	t.Helper()
	p, err := dockertest.NewPool("")
	if err != nil {
		t.Fatalf("dockertest: %v", err)
	}
	if err := p.Client.Ping(); err != nil {
		t.Skipf("Docker không sẵn sàng, bỏ qua test tích hợp: %v", err)
	}
	return p
}

// PostgresPool starts postgres:17-alpine and returns a ready pgx pool plus the
// DSN. The container is removed when the test ends.
func PostgresPool(t *testing.T) (*pgxpool.Pool, string) {
	t.Helper()
	p := pool(t)

	res, err := p.RunWithOptions(&dockertest.RunOptions{
		Repository: "postgres",
		Tag:        "17-alpine",
		Env: []string{
			"POSTGRES_PASSWORD=test",
			"POSTGRES_USER=test",
			"POSTGRES_DB=test",
			"listen_addresses=*",
		},
	}, func(c *docker.HostConfig) {
		c.AutoRemove = true
		c.RestartPolicy = docker.RestartPolicy{Name: "no"}
	})
	if err != nil {
		t.Fatalf("run postgres: %v", err)
	}
	t.Cleanup(func() { _ = p.Purge(res) })
	_ = res.Expire(120)

	dsn := fmt.Sprintf("postgres://test:test@%s/test?sslmode=disable", res.GetHostPort("5432/tcp"))

	var pgpool *pgxpool.Pool
	p.MaxWait = 60 * time.Second
	if err := p.Retry(func() error {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		pp, err := pgxpool.New(ctx, dsn)
		if err != nil {
			return err
		}
		if err := pp.Ping(ctx); err != nil {
			pp.Close()
			return err
		}
		pgpool = pp
		return nil
	}); err != nil {
		t.Fatalf("postgres không lên: %v", err)
	}
	t.Cleanup(pgpool.Close)
	return pgpool, dsn
}

// MongoDB starts mongo:7 and returns a ready *mongo.Database.
func MongoDB(t *testing.T) *mongo.Database {
	t.Helper()
	p := pool(t)

	res, err := p.RunWithOptions(&dockertest.RunOptions{
		Repository: "mongo",
		Tag:        "7",
	}, func(c *docker.HostConfig) {
		c.AutoRemove = true
		c.RestartPolicy = docker.RestartPolicy{Name: "no"}
	})
	if err != nil {
		t.Fatalf("run mongo: %v", err)
	}
	t.Cleanup(func() { _ = p.Purge(res) })
	_ = res.Expire(120)

	uri := fmt.Sprintf("mongodb://%s", res.GetHostPort("27017/tcp"))

	var db *mongo.Database
	p.MaxWait = 60 * time.Second
	if err := p.Retry(func() error {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		client, err := mongo.Connect(ctx, mongoopts.Client().ApplyURI(uri))
		if err != nil {
			return err
		}
		if err := client.Ping(ctx, nil); err != nil {
			return err
		}
		db = client.Database("itest")
		return nil
	}); err != nil {
		t.Fatalf("mongo không lên: %v", err)
	}
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = db.Client().Disconnect(ctx)
	})
	return db
}
