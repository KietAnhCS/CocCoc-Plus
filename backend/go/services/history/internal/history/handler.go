package history

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"go.mongodb.org/mongo-driver/bson/primitive"

	"github.com/vnsearch/backend-go/platform/auth"
	"github.com/vnsearch/backend-go/platform/httpx"
)

type Handler struct {
	svc *Service
}

func NewHandler(svc *Service) *Handler { return &Handler{svc: svc} }

func (h *Handler) Register(r chi.Router) {
	r.Post("/visits", h.recordVisit)
	r.Get("/visits", h.visitHistory)
	r.Delete("/visits/{id}", h.deleteVisit)
	r.Delete("/visits", h.deleteRange)
	r.Post("/searches", h.recordSearch)
	r.Get("/searches", h.searchHistory)
	r.Get("/searches/suggest", h.suggest)
	r.Get("/summary", h.summary)
}

type visitRequest struct {
	URL   string `json:"url"`
	Title string `json:"title"`
}

type searchRequest struct {
	Query       string `json:"query"`
	ResultCount int    `json:"resultCount"`
}

func (h *Handler) recordVisit(w http.ResponseWriter, r *http.Request) {
	username := auth.Username(r.Context())
	var req visitRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.Error(w, http.StatusBadRequest, "Thân request không phải JSON hợp lệ.")
		return
	}
	if strings.TrimSpace(req.URL) == "" {
		httpx.Error(w, http.StatusBadRequest, "Thiếu địa chỉ trang")
		return
	}
	if len(req.URL) > 2048 {
		httpx.Error(w, http.StatusBadRequest, "Địa chỉ quá dài")
		return
	}
	if len(req.Title) > 512 {
		httpx.Error(w, http.StatusBadRequest, "Tiêu đề quá dài")
		return
	}
	saved, err := h.svc.RecordVisit(r.Context(), username, req.URL, req.Title, false)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "Không ghi được lịch sử.")
		return
	}
	if saved == nil {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	httpx.WriteJSON(w, http.StatusCreated, saved)
}

func (h *Handler) visitHistory(w http.ResponseWriter, r *http.Request) {
	username := auth.Username(r.Context())
	q := r.URL.Query().Get("q")
	from, ok1 := parseInstant(r, "from")
	to, ok2 := parseInstant(r, "to")
	if !ok1 || !ok2 {
		httpx.Error(w, http.StatusBadRequest, "Tham số thời gian phải theo định dạng ISO-8601.")
		return
	}
	page := queryInt(r, "page", 0)
	size := queryInt(r, "size", 50)

	result, err := h.svc.VisitHistory(r.Context(), username, q, from, to, page, size)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "Không đọc được lịch sử.")
		return
	}
	httpx.WriteJSON(w, http.StatusOK, result)
}

func (h *Handler) deleteVisit(w http.ResponseWriter, r *http.Request) {
	username := auth.Username(r.Context())
	id, err := primitive.ObjectIDFromHex(chi.URLParam(r, "id"))
	if err != nil {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	deleted, err := h.svc.DeleteVisit(r.Context(), username, id)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "Không xoá được mục.")
		return
	}
	if !deleted {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) deleteRange(w http.ResponseWriter, r *http.Request) {
	username := auth.Username(r.Context())
	from, ok1 := parseInstant(r, "from")
	to, ok2 := parseInstant(r, "to")
	if !ok1 || !ok2 {
		httpx.Error(w, http.StatusBadRequest, "Tham số thời gian phải theo định dạng ISO-8601.")
		return
	}
	n, err := h.svc.DeleteRange(r.Context(), username, from, to)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "Không xoá được lịch sử.")
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"deleted": n})
}

func (h *Handler) recordSearch(w http.ResponseWriter, r *http.Request) {
	username := auth.Username(r.Context())
	var req searchRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.Error(w, http.StatusBadRequest, "Thân request không phải JSON hợp lệ.")
		return
	}
	if strings.TrimSpace(req.Query) == "" {
		httpx.Error(w, http.StatusBadRequest, "Thiếu truy vấn")
		return
	}
	if len(req.Query) > 200 {
		httpx.Error(w, http.StatusBadRequest, "Truy vấn quá dài")
		return
	}
	saved, err := h.svc.RecordSearch(r.Context(), username, req.Query, req.ResultCount)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "Không ghi được truy vấn.")
		return
	}
	if saved == nil {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	httpx.WriteJSON(w, http.StatusCreated, saved)
}

func (h *Handler) searchHistory(w http.ResponseWriter, r *http.Request) {
	username := auth.Username(r.Context())
	page := queryInt(r, "page", 0)
	size := queryInt(r, "size", 50)
	result, err := h.svc.SearchHistory(r.Context(), username, page, size)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "Không đọc được lịch sử tìm kiếm.")
		return
	}
	httpx.WriteJSON(w, http.StatusOK, result)
}

func (h *Handler) suggest(w http.ResponseWriter, r *http.Request) {
	username := auth.Username(r.Context())
	prefix := r.URL.Query().Get("prefix")
	limit := queryInt(r, "limit", 8)
	out, err := h.svc.Suggest(r.Context(), username, prefix, limit)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "Không lấy được gợi ý.")
		return
	}
	httpx.WriteJSON(w, http.StatusOK, out)
}

func (h *Handler) summary(w http.ResponseWriter, r *http.Request) {
	username := auth.Username(r.Context())
	n, err := h.svc.CountVisits(r.Context(), username)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "Không đếm được lịch sử.")
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"visits": n})
}

func parseInstant(r *http.Request, key string) (*time.Time, bool) {
	raw := r.URL.Query().Get(key)
	if raw == "" {
		return nil, true
	}
	t, err := time.Parse(time.RFC3339, raw)
	if err != nil {
		return nil, false
	}
	u := t.UTC()
	return &u, true
}

func queryInt(r *http.Request, key string, def int) int {
	raw := r.URL.Query().Get(key)
	if raw == "" {
		return def
	}
	v, err := strconv.Atoi(raw)
	if err != nil {
		return def
	}
	return v
}
