package settings

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strconv"
	"time"

	"github.com/go-chi/chi/v5"

	"github.com/vnsearch/backend-go/platform/auth"
	"github.com/vnsearch/backend-go/platform/httpx"
)

const maxJSONBytes = 64 * 1024

type store interface {
	Read(ctx context.Context, username string) (*Snapshot, error)
	Merge(ctx context.Context, username, newJSON string, expected *int64) (*Snapshot, error)
	Replace(ctx context.Context, username, newJSON string, expected *int64) (*Snapshot, error)
	DeleteKey(ctx context.Context, username, key string) (*Snapshot, error)
	DeleteAll(ctx context.Context, username string) error
}

type auditor interface {
	Record(subject, action, resource, outcome, detail string)
}

type Handler struct {
	repo  store
	audit auditor
}

func NewHandler(repo store, audit auditor) *Handler {
	return &Handler{repo: repo, audit: audit}
}

func (h *Handler) Register(r chi.Router) {
	r.Get("/", h.read)
	r.Patch("/", h.merge)
	r.Put("/", h.replace)
	r.Delete("/", h.resetToDefaults)
	r.Delete("/{key}", h.deleteKey)
}

func (h *Handler) read(w http.ResponseWriter, r *http.Request) {
	username := auth.Username(r.Context())
	snap, err := h.repo.Read(r.Context(), username)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "Không đọc được tuỳ chọn.")
		return
	}
	if snap == nil {
		httpx.WriteJSON(w, http.StatusOK, map[string]any{"settings": map[string]any{}, "version": 0})
		return
	}
	writeSnapshot(w, snap)
}

func (h *Handler) merge(w http.ResponseWriter, r *http.Request)   { h.write(w, r, true) }
func (h *Handler) replace(w http.ResponseWriter, r *http.Request) { h.write(w, r, false) }

func (h *Handler) write(w http.ResponseWriter, r *http.Request, merge bool) {
	username := auth.Username(r.Context())

	body, _ := io.ReadAll(io.LimitReader(r.Body, maxJSONBytes+1))
	clean, ok := validate(body)
	if !ok {
		httpx.Error(w, http.StatusBadRequest, "Tuỳ chọn phải là một đối tượng JSON hợp lệ (tối đa 64 KB).")
		return
	}

	expected := ifMatch(r)

	var (
		snap *Snapshot
		err  error
	)
	if merge {
		snap, err = h.repo.Merge(r.Context(), username, clean, expected)
	} else {
		snap, err = h.repo.Replace(r.Context(), username, clean, expected)
	}
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "Không ghi được tuỳ chọn.")
		return
	}

	if snap == nil {
		current, _ := h.repo.Read(r.Context(), username)
		curJSON := "{}"
		var curVer int64
		if current != nil {
			curJSON, curVer = current.JSON, current.Version
		}
		httpx.WriteJSON(w, http.StatusConflict, map[string]any{
			"error":    "conflict",
			"message":  "Thiết bị khác đã sửa tuỳ chọn. Hãy gộp rồi thử lại.",
			"settings": json.RawMessage(curJSON),
			"version":  curVer,
		})
		return
	}

	action := "SETTINGS_REPLACE"
	if merge {
		action = "SETTINGS_MERGE"
	}
	h.audit.Record(username, action, "user_settings:"+username, "SUCCESS", "")
	writeSnapshot(w, snap)
}

func (h *Handler) deleteKey(w http.ResponseWriter, r *http.Request) {
	username := auth.Username(r.Context())
	key := chi.URLParam(r, "key")

	existing, err := h.repo.Read(r.Context(), username)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "Không đọc được tuỳ chọn.")
		return
	}
	if existing == nil {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	snap, err := h.repo.DeleteKey(r.Context(), username, key)
	if err != nil || snap == nil {
		httpx.Error(w, http.StatusInternalServerError, "Không xoá được khoá.")
		return
	}
	h.audit.Record(username, "SETTINGS_DELETE_KEY", "user_settings:"+username, "SUCCESS", "khoa="+key)
	writeSnapshot(w, snap)
}

func (h *Handler) resetToDefaults(w http.ResponseWriter, r *http.Request) {
	username := auth.Username(r.Context())
	if err := h.repo.DeleteAll(r.Context(), username); err != nil {
		httpx.Error(w, http.StatusInternalServerError, "Không khôi phục được mặc định.")
		return
	}
	h.audit.Record(username, "SETTINGS_RESET", "user_settings:"+username, "SUCCESS", "")
	w.WriteHeader(http.StatusNoContent)
}

func writeSnapshot(w http.ResponseWriter, snap *Snapshot) {
	w.Header().Set("ETag", `"`+strconv.FormatInt(snap.Version, 10)+`"`)
	httpx.WriteJSON(w, http.StatusOK, map[string]any{
		"settings":  json.RawMessage(snap.JSON),
		"version":   snap.Version,
		"updatedAt": snap.UpdatedAt.UTC().Format(time.RFC3339),
	})
}

func validate(body []byte) (string, bool) {
	if len(body) == 0 || len(body) > maxJSONBytes {
		return "", false
	}
	var probe any
	if err := json.Unmarshal(body, &probe); err != nil {
		return "", false
	}
	obj, ok := probe.(map[string]any)
	if !ok {
		return "", false
	}
	out, err := json.Marshal(obj)
	if err != nil {
		return "", false
	}
	return string(out), true
}

func ifMatch(r *http.Request) *int64 {
	raw := r.Header.Get("If-Match")
	if raw == "" {
		return nil
	}
	raw = trimQuotes(raw)
	v, err := strconv.ParseInt(raw, 10, 64)
	if err != nil {
		return nil
	}
	return &v
}

func trimQuotes(s string) string {
	if len(s) >= 2 && s[0] == '"' && s[len(s)-1] == '"' {
		return s[1 : len(s)-1]
	}
	return s
}
