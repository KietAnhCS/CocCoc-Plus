package middleware

import (
	"net/http"
	"strings"
)

func SecurityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Frame-Options", "DENY")
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
		next.ServeHTTP(w, r)
	})
}

type CORSOptions struct {
	AllowedOrigins []string
}

var corsAllowMethods = "GET, POST, PUT, PATCH, DELETE, OPTIONS"
var corsAllowHeaders = "Accept, Content-Type, Authorization, X-API-Key, X-Device-Id, If-Match"

func CORS(opts CORSOptions) func(http.Handler) http.Handler {
	patterns := make([]string, 0, len(opts.AllowedOrigins)+2)
	for _, o := range opts.AllowedOrigins {
		if o = strings.TrimSpace(o); o != "" {
			patterns = append(patterns, o)
		}
	}
	patterns = append(patterns, "file://", "null")

	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			origin := r.Header.Get("Origin")
			if origin != "" && originAllowed(origin, patterns) {
				h := w.Header()
				h.Set("Access-Control-Allow-Origin", origin)
				h.Set("Vary", "Origin")
				h.Set("Access-Control-Allow-Methods", corsAllowMethods)
				h.Set("Access-Control-Allow-Headers", corsAllowHeaders)
				h.Set("Access-Control-Max-Age", "3600")
			}
			if r.Method == http.MethodOptions {
				w.WriteHeader(http.StatusNoContent)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}

func originAllowed(origin string, patterns []string) bool {
	for _, p := range patterns {
		if p == origin {
			return true
		}
		if strings.HasSuffix(p, "*") && strings.HasPrefix(origin, strings.TrimSuffix(p, "*")) {
			return true
		}
		if p == "file://" && strings.HasPrefix(origin, "file://") {
			return true
		}
	}
	return false
}
