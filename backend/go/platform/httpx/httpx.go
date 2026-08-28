package httpx

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"log/slog"
	"net/http"
	"time"
)

type problem struct {
	Type      string `json:"type"`
	Title     string `json:"title"`
	Status    int    `json:"status"`
	Detail    string `json:"detail"`
	Message   string `json:"message"`
	Timestamp string `json:"timestamp"`
	Reference string `json:"reference,omitempty"`
}

func WriteJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json;charset=UTF-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func Error(w http.ResponseWriter, status int, message string) {
	writeProblem(w, status, message, "")
}

func ErrorRef(w http.ResponseWriter, status int, message, reference string) {
	writeProblem(w, status, message, reference)
}

func writeProblem(w http.ResponseWriter, status int, message, reference string) {
	body := problem{
		Type:      "https://vnsearch.dev/errors/" + itoa(status),
		Title:     http.StatusText(status),
		Status:    status,
		Detail:    message,
		Message:   message,
		Timestamp: time.Now().UTC().Format(time.RFC3339),
		Reference: reference,
	}
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	return string(buf[i:])
}

func Recoverer(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if rec := recover(); rec != nil {
				ref := refID()
				slog.Error("unhandled panic",
					"reference", ref, "method", r.Method, "path", r.URL.Path, "panic", rec)
				ErrorRef(w, http.StatusInternalServerError,
					"Đã xảy ra lỗi hệ thống. Vui lòng cung cấp mã tham chiếu khi báo lỗi.", ref)
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func refID() string {
	var b [4]byte
	_, _ = rand.Read(b[:])
	return hex.EncodeToString(b[:])
}
