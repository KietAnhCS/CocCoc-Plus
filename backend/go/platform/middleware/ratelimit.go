package middleware

import (
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

const maxTrackedClients = 100_000

type bucket struct {
	mu             sync.Mutex
	capacity       float64
	tokensPerMilli float64
	tokens         float64
	lastRefill     int64
}

func newBucket(rpm int, nowMillis int64) *bucket {
	return &bucket{
		capacity:       float64(rpm),
		tokensPerMilli: float64(rpm) / 60_000.0,
		tokens:         float64(rpm),
		lastRefill:     nowMillis,
	}
}

func (b *bucket) tryConsume(nowMillis int64) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	elapsed := nowMillis - b.lastRefill
	if elapsed < 0 {
		elapsed = 0
	}
	b.lastRefill = nowMillis
	b.tokens = min(b.capacity, b.tokens+float64(elapsed)*b.tokensPerMilli)
	if b.tokens < 1.0 {
		return false
	}
	b.tokens -= 1.0
	return true
}

type RateLimiter struct {
	rpm        int
	enabled    bool
	trustProxy bool
	mu         sync.Mutex
	buckets    map[string]*bucket
	now        func() time.Time
}

func NewRateLimiter(rpm int, enabled, trustProxy bool) *RateLimiter {
	if rpm <= 0 {
		rpm = 120
	}
	return &RateLimiter{
		rpm:        rpm,
		enabled:    enabled,
		trustProxy: trustProxy,
		buckets:    make(map[string]*bucket),
		now:        time.Now,
	}
}

func (rl *RateLimiter) Handler(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !rl.enabled {
			next.ServeHTTP(w, r)
			return
		}
		nowMillis := rl.now().UnixMilli()
		b := rl.bucketFor(rl.clientIP(r), nowMillis)
		if !b.tryConsume(nowMillis) {
			w.Header().Set("Retry-After", "60")
			w.Header().Set("Content-Type", "application/json;charset=UTF-8")
			w.WriteHeader(http.StatusTooManyRequests)
			_, _ = w.Write([]byte(`{"status":429,"error":"Too Many Requests","message":"Vượt quá giới hạn ` +
				itoa(rl.rpm) + ` request/phút"}`))
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (rl *RateLimiter) bucketFor(key string, nowMillis int64) *bucket {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	if len(rl.buckets) > maxTrackedClients {
		rl.buckets = make(map[string]*bucket)
	}
	b, ok := rl.buckets[key]
	if !ok {
		b = newBucket(rl.rpm, nowMillis)
		rl.buckets[key] = b
	}
	return b
}

func (rl *RateLimiter) clientIP(r *http.Request) string {
	if rl.trustProxy {
		if fwd := r.Header.Get("X-Forwarded-For"); fwd != "" {
			if comma := strings.IndexByte(fwd, ','); comma > 0 {
				return strings.TrimSpace(fwd[:comma])
			}
			return strings.TrimSpace(fwd)
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}
