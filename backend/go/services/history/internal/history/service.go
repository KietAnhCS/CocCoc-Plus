package history

import (
	"context"
	"log/slog"
	"net/url"
	"regexp"
	"strings"
	"time"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/bson/primitive"
)

const (
	maxPageSize    = 200
	maxURLLength   = 2048
	maxTitleLength = 512
	maxQueryLength = 200
)

var wsRun = regexp.MustCompile(`\s+`)

type store interface {
	FindVisitByURL(ctx context.Context, username, url string) (*Visit, error)
	UpsertVisit(ctx context.Context, v Visit) (Visit, error)
	ListVisits(ctx context.Context, filter bson.M, page, size int) ([]Visit, int64, error)
	DeleteVisit(ctx context.Context, id primitive.ObjectID, username string) (int64, error)
	DeleteVisitsBetween(ctx context.Context, username string, from, to time.Time) (int64, error)
	CountVisits(ctx context.Context, username string) (int64, error)
	FindQueryByNormalized(ctx context.Context, username, normalized string) (*SearchQuery, error)
	UpsertQuery(ctx context.Context, q SearchQuery) (SearchQuery, error)
	ListQueries(ctx context.Context, username string, page, size int) ([]SearchQuery, int64, error)
	SuggestQueries(ctx context.Context, username, anchoredPattern string, limit int) ([]SearchQuery, error)
	DeleteQueriesBetween(ctx context.Context, username string, from, to time.Time) (int64, error)
	RecordAudit(ctx context.Context, subject, action, resource, outcome, detail string) error
}

type Service struct {
	store store
	clock func() time.Time
}

func NewService(st store, clock func() time.Time) *Service {
	if clock == nil {
		clock = func() time.Time { return time.Now().UTC() }
	}
	return &Service{store: st, clock: clock}
}

func (s *Service) RecordVisit(ctx context.Context, username, rawURL, title string, incognito bool) (*Visit, error) {
	if incognito {
		slog.Warn("từ chối ghi lịch sử cho lượt ghé ẩn danh", "username", username)
		return nil, nil
	}
	cleanURL := truncate(rawURL, maxURLLength)
	if cleanURL == "" {
		return nil, nil
	}
	now := s.clock()

	existing, err := s.store.FindVisitByURL(ctx, username, cleanURL)
	if err != nil {
		return nil, err
	}
	if existing != nil {
		updated := Visit{
			ID: existing.ID, Username: username, URL: cleanURL,
			Title: truncate(title, maxTitleLength), Host: existing.Host,
			VisitedAt: now, VisitCount: existing.VisitCount + 1, Incognito: false,
		}
		v, err := s.store.UpsertVisit(ctx, updated)
		return &v, err
	}
	fresh := Visit{
		Username: username, URL: cleanURL, Title: truncate(title, maxTitleLength),
		Host: hostOf(cleanURL), VisitedAt: now, VisitCount: 1, Incognito: false,
	}
	v, err := s.store.UpsertVisit(ctx, fresh)
	return &v, err
}

func (s *Service) RecordSearch(ctx context.Context, username, query string, resultCount int) (*SearchQuery, error) {
	clean := truncate(query, maxQueryLength)
	if clean == "" {
		return nil, nil
	}
	normalized := normalize(clean)
	now := s.clock()

	existing, err := s.store.FindQueryByNormalized(ctx, username, normalized)
	if err != nil {
		return nil, err
	}
	q := SearchQuery{
		Username: username, Query: clean, Normalized: normalized,
		ResultCount: resultCount, SearchedAt: now,
	}
	if existing != nil {
		q.ID = existing.ID
	}
	saved, err := s.store.UpsertQuery(ctx, q)
	return &saved, err
}

func (s *Service) VisitHistory(ctx context.Context, username, keyword string, from, to *time.Time,
	page, size int) (Page[Visit], error) {

	page = maxInt(page, 0)
	size = clamp(size)

	filter := bson.M{"username": username}
	switch {
	case strings.TrimSpace(keyword) != "":
		rx := primitive.Regex{Pattern: quoteRegex(keyword), Options: "i"}
		filter["$or"] = bson.A{bson.M{"title": bson.M{"$regex": rx}}, bson.M{"url": bson.M{"$regex": rx}}}
	case from != nil && to != nil:
		filter["visitedAt"] = bson.M{"$gte": *from, "$lte": *to}
	}

	items, total, err := s.store.ListVisits(ctx, filter, page, size)
	if err != nil {
		return Page[Visit]{}, err
	}
	return newPage(items, page, size, total), nil
}

func (s *Service) Suggest(ctx context.Context, username, prefix string, size int) ([]SearchQuery, error) {
	if strings.TrimSpace(prefix) == "" {
		return []SearchQuery{}, nil
	}
	pattern := "^" + quoteRegex(normalize(prefix))
	out, err := s.store.SuggestQueries(ctx, username, pattern, clamp(size))
	if err != nil {
		return nil, err
	}
	if out == nil {
		out = []SearchQuery{}
	}
	return out, nil
}

func (s *Service) SearchHistory(ctx context.Context, username string, page, size int) (Page[SearchQuery], error) {
	page = maxInt(page, 0)
	size = clamp(size)
	items, total, err := s.store.ListQueries(ctx, username, page, size)
	if err != nil {
		return Page[SearchQuery]{}, err
	}
	return newPage(items, page, size, total), nil
}

func (s *Service) DeleteVisit(ctx context.Context, username string, id primitive.ObjectID) (bool, error) {
	n, err := s.store.DeleteVisit(ctx, id, username)
	if err != nil {
		return false, err
	}
	if n > 0 {
		_ = s.store.RecordAudit(ctx, username, "HISTORY_DELETE_ONE", "visits:"+id.Hex(), "SUCCESS", "")
	}
	return n > 0, nil
}

func (s *Service) DeleteRange(ctx context.Context, username string, from, to *time.Time) (int64, error) {
	start := time.Unix(0, 0).UTC()
	if from != nil {
		start = *from
	}
	end := s.clock()
	if to != nil {
		end = *to
	}
	vc, err := s.store.DeleteVisitsBetween(ctx, username, start, end)
	if err != nil {
		return 0, err
	}
	qc, err := s.store.DeleteQueriesBetween(ctx, username, start, end)
	if err != nil {
		return 0, err
	}
	slog.Info("xoá lịch sử", "username", username, "visits", vc, "queries", qc)
	_ = s.store.RecordAudit(ctx, username, "HISTORY_DELETE_RANGE", "visits+searches:"+username,
		"SUCCESS", "")
	return vc + qc, nil
}

func (s *Service) CountVisits(ctx context.Context, username string) (int64, error) {
	return s.store.CountVisits(ctx, username)
}

func clamp(size int) int {
	if size < 1 {
		return 1
	}
	if size > maxPageSize {
		return maxPageSize
	}
	return size
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func truncate(value string, max int) string {
	t := strings.TrimSpace(value)
	if len(t) <= max {
		return t
	}
	return t[:max]
}

func normalize(value string) string {
	return wsRun.ReplaceAllString(strings.ToLower(strings.TrimSpace(value)), " ")
}

func quoteRegex(value string) string {
	return regexp.QuoteMeta(value)
}

func hostOf(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return ""
	}
	return strings.TrimPrefix(u.Hostname(), "www.")
}
