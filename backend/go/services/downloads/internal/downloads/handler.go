package downloads

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"

	"github.com/vnsearch/backend-go/platform/auth"
	"github.com/vnsearch/backend-go/platform/httpx"
)

type auditor interface {
	Record(subject, action, resource, outcome, detail string)
}

type Handler struct {
	svc   *Service
	audit auditor
}

func NewHandler(svc *Service, audit auditor) *Handler {
	return &Handler{svc: svc, audit: audit}
}

func (h *Handler) Register(r chi.Router) {
	r.Post("/", h.start)
	r.Patch("/{id}", h.update)
	r.Get("/", h.list)
	r.Get("/active", h.listActive)
	r.Get("/summary", h.summary)
	r.Delete("/{id}", h.delete)
	r.Delete("/", h.deleteFinished)
}

type startRequest struct {
	ID         string `json:"id"`
	SourceURL  string `json:"sourceUrl"`
	FileName   string `json:"fileName"`
	MimeType   string `json:"mimeType"`
	TotalBytes *int64 `json:"totalBytes"`
	LocalPath  string `json:"localPath"`
}

type updateRequest struct {
	ReceivedBytes *int64  `json:"receivedBytes"`
	State         *string `json:"state"`
	LocalPath     *string `json:"localPath"`
}

func (h *Handler) start(w http.ResponseWriter, r *http.Request) {
	username := auth.Username(r.Context())
	deviceID := r.Header.Get("X-Device-Id")

	var req startRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.Error(w, http.StatusBadRequest, "Thân request không phải JSON hợp lệ.")
		return
	}
	if strings.TrimSpace(req.ID) == "" {
		httpx.Error(w, http.StatusBadRequest, "Thiếu id")
		return
	}
	if strings.TrimSpace(req.SourceURL) == "" {
		httpx.Error(w, http.StatusBadRequest, "Thiếu địa chỉ nguồn")
		return
	}
	if strings.TrimSpace(req.FileName) == "" {
		httpx.Error(w, http.StatusBadRequest, "Thiếu tên tệp")
		return
	}
	if len(req.FileName) > 255 {
		httpx.Error(w, http.StatusBadRequest, "Tên tệp quá dài")
		return
	}
	if len(req.MimeType) > 255 {
		httpx.Error(w, http.StatusBadRequest, "mimeType quá dài")
		return
	}
	id, err := uuid.Parse(req.ID)
	if err != nil {
		httpx.Error(w, http.StatusBadRequest, "Mã tải xuống không hợp lệ (phải là UUID).")
		return
	}

	rec, err := h.svc.Start(r.Context(), username, id, req.SourceURL, req.FileName,
		req.MimeType, req.TotalBytes, req.LocalPath, deviceID)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "Không tạo được bản ghi tải xuống.")
		return
	}
	httpx.WriteJSON(w, http.StatusCreated, rec.ToPublic(deviceID))
}

func (h *Handler) update(w http.ResponseWriter, r *http.Request) {
	username := auth.Username(r.Context())
	deviceID := r.Header.Get("X-Device-Id")

	id, err := uuid.Parse(chi.URLParam(r, "id"))
	if err != nil {
		httpx.Error(w, http.StatusBadRequest, "Mã tải xuống không hợp lệ (phải là UUID).")
		return
	}

	var req updateRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.Error(w, http.StatusBadRequest, "Thân request không phải JSON hợp lệ.")
		return
	}
	var state *State
	if req.State != nil {
		if !ValidState(*req.State) {
			httpx.Error(w, http.StatusBadRequest, "Trạng thái không hợp lệ.")
			return
		}
		s := State(*req.State)
		state = &s
	}

	rec, err := h.svc.Update(r.Context(), username, id, req.ReceivedBytes, state, req.LocalPath)
	if err != nil {
		var it *InvalidTransitionError
		if errors.As(err, &it) {
			httpx.Error(w, http.StatusConflict, it.Error())
			return
		}
		httpx.Error(w, http.StatusInternalServerError, "Không cập nhật được bản ghi.")
		return
	}
	if rec == nil {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	httpx.WriteJSON(w, http.StatusOK, rec.ToPublic(deviceID))
}

func (h *Handler) list(w http.ResponseWriter, r *http.Request) {
	username := auth.Username(r.Context())
	deviceID := r.Header.Get("X-Device-Id")
	page := queryInt(r, "page", 0)
	size := queryInt(r, "size", 50)

	recs, err := h.svc.List(r.Context(), username, page, size)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "Không đọc được sổ tải xuống.")
		return
	}
	httpx.WriteJSON(w, http.StatusOK, toPublicList(recs, deviceID))
}

func (h *Handler) listActive(w http.ResponseWriter, r *http.Request) {
	username := auth.Username(r.Context())
	deviceID := r.Header.Get("X-Device-Id")
	recs, err := h.svc.ListActive(r.Context(), username)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "Không đọc được sổ tải xuống.")
		return
	}
	httpx.WriteJSON(w, http.StatusOK, toPublicList(recs, deviceID))
}

func (h *Handler) delete(w http.ResponseWriter, r *http.Request) {
	username := auth.Username(r.Context())
	id, err := uuid.Parse(chi.URLParam(r, "id"))
	if err != nil {
		httpx.Error(w, http.StatusBadRequest, "Mã tải xuống không hợp lệ (phải là UUID).")
		return
	}
	deleted, err := h.svc.Delete(r.Context(), username, id)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "Không xoá được bản ghi.")
		return
	}
	if !deleted {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	h.audit.Record(username, "DOWNLOAD_DELETE", "downloads:"+id.String(), "SUCCESS", "")
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) deleteFinished(w http.ResponseWriter, r *http.Request) {
	username := auth.Username(r.Context())
	n, err := h.svc.DeleteFinished(r.Context(), username)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "Không xoá được sổ tải xuống.")
		return
	}
	h.audit.Record(username, "DOWNLOAD_DELETE_ALL", "", "SUCCESS", "deleted="+strconv.Itoa(n))
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"deleted": n})
}

func (h *Handler) summary(w http.ResponseWriter, r *http.Request) {
	username := auth.Username(r.Context())
	n, err := h.svc.Count(r.Context(), username)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "Không đếm được sổ tải xuống.")
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"total": n})
}

func toPublicList(recs []Record, deviceID string) []PublicView {
	out := make([]PublicView, 0, len(recs))
	for _, rec := range recs {
		out = append(out, rec.ToPublic(deviceID))
	}
	return out
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
