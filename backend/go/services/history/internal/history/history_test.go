package history

import (
	"context"
	"testing"
	"time"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/bson/primitive"
)

func TestNormalize(t *testing.T) {
	if got := normalize("  Máy   Tính  "); got != "máy tính" {
		t.Fatalf("normalize = %q", got)
	}
}

func TestQuoteRegexNeutralisesMetacharacters(t *testing.T) {
	got := quoteRegex("(a+)+$")
	if got == "(a+)+$" {
		t.Fatal("metacharacters not escaped")
	}
}

func TestHostOf(t *testing.T) {
	if got := hostOf("https://www.example.com/path?q=1"); got != "example.com" {
		t.Fatalf("hostOf = %q", got)
	}
	if got := hostOf("not a url"); got != "" {
		t.Fatalf("hostOf(bad) = %q", got)
	}
}

func TestTruncate(t *testing.T) {
	if got := truncate("  abcdef  ", 3); got != "abc" {
		t.Fatalf("truncate = %q", got)
	}
}

func TestNewPageMath(t *testing.T) {
	p := newPage([]int{1, 2}, 1, 2, 5)
	if p.TotalPages != 3 || p.First || p.Last || p.NumberOfElements != 2 {
		t.Fatalf("page = %+v", p)
	}
	empty := newPage[int](nil, 0, 10, 0)
	if !empty.Empty || !empty.First || !empty.Last || empty.Content == nil {
		t.Fatalf("empty page = %+v", empty)
	}
}

type fakeStore struct {
	visitByURL  *Visit
	upserted    Visit
	queryByNorm *SearchQuery
}

func (f *fakeStore) FindVisitByURL(context.Context, string, string) (*Visit, error) {
	return f.visitByURL, nil
}
func (f *fakeStore) UpsertVisit(_ context.Context, v Visit) (Visit, error) {
	f.upserted = v
	return v, nil
}
func (f *fakeStore) ListVisits(context.Context, bson.M, int, int) ([]Visit, int64, error) {
	return nil, 0, nil
}
func (f *fakeStore) DeleteVisit(context.Context, primitive.ObjectID, string) (int64, error) {
	return 0, nil
}
func (f *fakeStore) DeleteVisitsBetween(context.Context, string, time.Time, time.Time) (int64, error) {
	return 0, nil
}
func (f *fakeStore) CountVisits(context.Context, string) (int64, error) { return 0, nil }
func (f *fakeStore) FindQueryByNormalized(context.Context, string, string) (*SearchQuery, error) {
	return f.queryByNorm, nil
}
func (f *fakeStore) UpsertQuery(_ context.Context, q SearchQuery) (SearchQuery, error) { return q, nil }
func (f *fakeStore) ListQueries(context.Context, string, int, int) ([]SearchQuery, int64, error) {
	return nil, 0, nil
}
func (f *fakeStore) SuggestQueries(context.Context, string, string, int) ([]SearchQuery, error) {
	return nil, nil
}
func (f *fakeStore) DeleteQueriesBetween(context.Context, string, time.Time, time.Time) (int64, error) {
	return 0, nil
}
func (f *fakeStore) RecordAudit(context.Context, string, string, string, string, string) error {
	return nil
}

func TestRecordVisitIncognitoIsDropped(t *testing.T) {
	svc := NewService(&fakeStore{}, nil)
	v, err := svc.RecordVisit(context.Background(), "kiet", "http://x", "t", true)
	if err != nil || v != nil {
		t.Fatalf("incognito visit stored: v=%v err=%v", v, err)
	}
}

func TestRecordVisitMergesOnSameURL(t *testing.T) {
	prev := &Visit{ID: primitive.NewObjectID(), Username: "kiet", URL: "http://x/a",
		Host: "x", VisitCount: 3}
	store := &fakeStore{visitByURL: prev}
	svc := NewService(store, func() time.Time { return time.Unix(1000, 0).UTC() })

	_, err := svc.RecordVisit(context.Background(), "kiet", "http://x/a", "new title", false)
	if err != nil {
		t.Fatal(err)
	}
	if store.upserted.ID != prev.ID {
		t.Fatal("merge should keep the same document id")
	}
	if store.upserted.VisitCount != 4 {
		t.Fatalf("visitCount = %d, want 4", store.upserted.VisitCount)
	}
	if store.upserted.Host != "x" {
		t.Fatalf("host = %q, want carried over", store.upserted.Host)
	}
}

func TestRecordVisitBlankURLReturnsNil(t *testing.T) {
	svc := NewService(&fakeStore{}, nil)
	v, _ := svc.RecordVisit(context.Background(), "kiet", "   ", "t", false)
	if v != nil {
		t.Fatal("blank url should not be stored")
	}
}

func TestSuggestBlankPrefix(t *testing.T) {
	svc := NewService(&fakeStore{}, nil)
	out, err := svc.Suggest(context.Background(), "kiet", "  ", 8)
	if err != nil || len(out) != 0 {
		t.Fatalf("out=%v err=%v", out, err)
	}
}
