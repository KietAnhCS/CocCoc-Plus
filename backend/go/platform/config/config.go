package config

import (
	"os"
	"strconv"
	"strings"
	"time"
)

func Env(key, fallback string) string {
	if v, ok := os.LookupEnv(key); ok && v != "" {
		return v
	}
	return fallback
}

func EnvInt(key string, fallback int) int {
	if v, ok := os.LookupEnv(key); ok && v != "" {
		if n, err := strconv.Atoi(strings.TrimSpace(v)); err == nil {
			return n
		}
	}
	return fallback
}

func EnvInt32(key string, fallback int32) int32 {
	return int32(EnvInt(key, int(fallback)))
}

func EnvBool(key string, fallback bool) bool {
	if v, ok := os.LookupEnv(key); ok && v != "" {
		if b, err := strconv.ParseBool(strings.TrimSpace(v)); err == nil {
			return b
		}
	}
	return fallback
}

func EnvDuration(key string, fallback time.Duration) time.Duration {
	if v, ok := os.LookupEnv(key); ok && v != "" {
		if d, err := ParseDuration(v); err == nil {
			return d
		}
	}
	return fallback
}

func ParseDuration(raw string) (time.Duration, error) {
	s := strings.TrimSpace(raw)
	if strings.HasSuffix(s, "d") {
		days, err := strconv.Atoi(strings.TrimSuffix(s, "d"))
		if err != nil {
			return 0, err
		}
		return time.Duration(days) * 24 * time.Hour, nil
	}
	return time.ParseDuration(s)
}
