package football

import (
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/go-chi/chi/v5"

	"github.com/vnsearch/backend-go/platform/httpx"
)

const (
	minSearchLength       = 3
	firstMonthOfNewSeason = 7
)

var datePattern = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}$`)

type Handler struct {
	svc   *Service
	clock func() time.Time
}

func NewHandler(svc *Service, clock func() time.Time) *Handler {
	if clock == nil {
		clock = func() time.Time { return time.Now().UTC() }
	}
	return &Handler{svc: svc, clock: clock}
}

func (h *Handler) Routes() chi.Router {
	r := chi.NewRouter()
	r.Get("/health", h.health)
	r.Get("/status", h.status)
	r.Get("/leagues", h.leagues)
	r.Get("/leagues/{id}/fixtures", h.leagueFixtures)
	r.Get("/fixtures", h.fixtures)
	r.Get("/teams", h.teams)
	r.Get("/teams/{id}/fixtures", h.teamFixtures)
	r.Get("/players", h.players)
	r.Get("/players/{id}", h.player)
	r.Put("/config/api-key", h.setAPIKey)
	return r
}

type meta struct {
	CachedAt time.Time `json:"cachedAt"`
	Source   string    `json:"source"`
	Stale    bool      `json:"stale"`
}

type envelope struct {
	Data any  `json:"data"`
	Meta meta `json:"meta"`
}

func envelopeFrom(data any, cachedAt time.Time, source Source, stale bool) envelope {
	return envelope{Data: data, Meta: meta{CachedAt: cachedAt, Source: string(source), Stale: stale}}
}

func ctlError(w http.ResponseWriter, status int, code, message string) {
	httpx.WriteJSON(w, status, map[string]any{
		"error": map[string]string{"code": code, "message": message},
	})
}

func (h *Handler) health(w http.ResponseWriter, r *http.Request) {
	httpx.WriteJSON(w, http.StatusOK, map[string]any{
		"status": "UP", "sampleOnly": !h.svc.HasAPIKey(),
	})
}

func (h *Handler) status(w http.ResponseWriter, r *http.Request) {
	used, err := h.svc.Used(r.Context())
	if err != nil {
		ctlError(w, http.StatusServiceUnavailable, "USAGE_UNAVAILABLE", "Không đọc được sổ hạn mức.")
		return
	}
	budget := h.svc.Budget()
	remaining := budget - used
	if remaining < 0 {
		remaining = 0
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{
		"used":       used,
		"budget":     budget,
		"remaining":  remaining,
		"sampleOnly": !h.svc.HasAPIKey(),
		"provider":   h.svc.ProviderName(),
	})
}

func (h *Handler) leagues(w http.ResponseWriter, r *http.Request) {
	country := strings.TrimSpace(r.URL.Query().Get("country"))
	search := strings.TrimSpace(r.URL.Query().Get("search"))
	p := h.svc.Leagues(r.Context(), country, search)
	httpx.WriteJSON(w, http.StatusOK, envelopeFrom(p.Data, p.CachedAt, p.Source, p.Stale()))
}

func (h *Handler) leagueFixtures(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	season := h.seasonOrCurrent(r.URL.Query().Get("season"))
	p := h.svc.LeagueFixtures(r.Context(), id, season)
	httpx.WriteJSON(w, http.StatusOK, envelopeFrom(p.Data, p.CachedAt, p.Source, p.Stale()))
}

func (h *Handler) fixtures(w http.ResponseWriter, r *http.Request) {
	day := strings.TrimSpace(r.URL.Query().Get("date"))
	if day == "" {
		day = h.clock().UTC().Format("2006-01-02")
	}
	if !datePattern.MatchString(day) {
		ctlError(w, http.StatusBadRequest, "BAD_DATE", "Tham số `date` phải có dạng YYYY-MM-DD.")
		return
	}
	if _, err := time.Parse("2006-01-02", day); err != nil {
		ctlError(w, http.StatusBadRequest, "BAD_DATE", "Ngày không tồn tại: "+day)
		return
	}
	league := strings.TrimSpace(r.URL.Query().Get("league"))
	season := strings.TrimSpace(r.URL.Query().Get("season"))
	p := h.svc.FixturesByDate(r.Context(), day, league, season)
	httpx.WriteJSON(w, http.StatusOK, envelopeFrom(p.Data, p.CachedAt, p.Source, p.Stale()))
}

func (h *Handler) teams(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimSpace(r.URL.Query().Get("search"))
	leagueID := strings.TrimSpace(r.URL.Query().Get("league"))
	if name == "" && leagueID == "" {
		ctlError(w, http.StatusBadRequest, "MISSING_FILTER",
			"Cần ít nhất một trong hai tham số: `search` hoặc `league`.")
		return
	}
	season := strings.TrimSpace(r.URL.Query().Get("season"))
	p := h.svc.Teams(r.Context(), name, leagueID, season)
	httpx.WriteJSON(w, http.StatusOK, envelopeFrom(p.Data, p.CachedAt, p.Source, p.Stale()))
}

func (h *Handler) teamFixtures(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	season := h.seasonOrCurrent(r.URL.Query().Get("season"))
	league := strings.TrimSpace(r.URL.Query().Get("league"))
	p := h.svc.TeamFixtures(r.Context(), id, season, league)
	httpx.WriteJSON(w, http.StatusOK, envelopeFrom(p.Data, p.CachedAt, p.Source, p.Stale()))
}

func (h *Handler) players(w http.ResponseWriter, r *http.Request) {
	needle := strings.TrimSpace(r.URL.Query().Get("search"))
	if utf8.RuneCountInString(needle) < minSearchLength {
		ctlError(w, http.StatusBadRequest, "SEARCH_TOO_SHORT", "Tham số `search` phải có ít nhất 3 ký tự.")
		return
	}
	p := h.svc.Players(r.Context(), needle)
	httpx.WriteJSON(w, http.StatusOK, envelopeFrom(p.Data, p.CachedAt, p.Source, p.Stale()))
}

func (h *Handler) player(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")
	year := h.seasonOrCurrent(r.URL.Query().Get("season"))
	p := h.svc.Player(r.Context(), id, year)
	if p.Data == nil {
		ctlError(w, http.StatusNotFound, "PLAYER_NOT_FOUND", "Không tìm thấy cầu thủ này ở mùa "+year+".")
		return
	}
	httpx.WriteJSON(w, http.StatusOK, envelopeFrom(p.Data, p.CachedAt, p.Source, p.Stale()))
}

func (h *Handler) setAPIKey(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Key string `json:"key"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	if err := h.svc.SetAPIKey(r.Context(), body.Key); err != nil {
		var pe *ProviderError
		if errors.As(err, &pe) {
			ctlError(w, http.StatusBadRequest, "KEY_REJECTED", pe.Error())
			return
		}
		ctlError(w, http.StatusBadRequest, "KEY_REJECTED", err.Error())
		return
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]any{"ok": true, "sampleOnly": false})
}

func (h *Handler) seasonOrCurrent(season string) string {
	if v := strings.TrimSpace(season); v != "" {
		return v
	}
	today := h.clock().UTC()
	year := today.Year()
	if int(today.Month()) < firstMonthOfNewSeason {
		year--
	}
	return strconv.Itoa(year)
}
