//go:build integration

package history

import (
	"context"
	"testing"
	"time"

	"go.mongodb.org/mongo-driver/bson"

	"github.com/vnsearch/backend-go/internal/itest"
)

func TestEnsureIndexesCreatesTTL(t *testing.T) {
	db := itest.MongoDB(t)
	s := NewStore(db)
	ctx := context.Background()
	if err := s.EnsureIndexes(ctx); err != nil {
		t.Fatal(err)
	}

	cur, err := db.Collection("visits").Indexes().List(ctx)
	if err != nil {
		t.Fatal(err)
	}
	var idx []bson.M
	if err := cur.All(ctx, &idx); err != nil {
		t.Fatal(err)
	}
	found := false
	for _, i := range idx {
		if i["name"] == "ix_visits_ttl" {
			found = true
			if i["expireAfterSeconds"] == nil {
				t.Fatal("ix_visits_ttl has no expireAfterSeconds")
			}
		}
	}
	if !found {
		t.Fatalf("ix_visits_ttl not created; indexes = %v", idx)
	}
}

func TestVisitAndQueryRoundTrip(t *testing.T) {
	db := itest.MongoDB(t)
	s := NewStore(db)
	svc := NewService(s, func() time.Time { return time.Unix(1_700_000_000, 0).UTC() })
	ctx := context.Background()

	if _, err := svc.RecordVisit(ctx, "kiet", "https://go.dev/doc", "Go docs", false); err != nil {
		t.Fatal(err)
	}
	// Same URL again -> merge, visitCount becomes 2, single document.
	if _, err := svc.RecordVisit(ctx, "kiet", "https://go.dev/doc", "Go docs v2", false); err != nil {
		t.Fatal(err)
	}
	page, err := svc.VisitHistory(ctx, "kiet", "", nil, nil, 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if page.TotalElements != 1 || page.Content[0].VisitCount != 2 {
		t.Fatalf("page = %+v", page)
	}
	if page.Content[0].Host != "go.dev" {
		t.Fatalf("host = %q", page.Content[0].Host)
	}

	// Search history + anchored prefix suggest.
	_, _ = svc.RecordSearch(ctx, "kiet", "máy tính", 12)
	_, _ = svc.RecordSearch(ctx, "kiet", "máy giặt", 3)
	_, _ = svc.RecordSearch(ctx, "kiet", "điện thoại", 7)

	sug, err := svc.Suggest(ctx, "kiet", "máy", 8)
	if err != nil {
		t.Fatal(err)
	}
	if len(sug) != 2 {
		t.Fatalf("suggest(\"máy\") returned %d, want 2", len(sug))
	}

	// Keyword search over title/url.
	kw, err := svc.VisitHistory(ctx, "kiet", "go.dev", nil, nil, 0, 10)
	if err != nil {
		t.Fatal(err)
	}
	if kw.TotalElements != 1 {
		t.Fatalf("keyword search total = %d, want 1", kw.TotalElements)
	}

	// Delete range wipes everything from EPOCH..now.
	n, err := svc.DeleteRange(ctx, "kiet", nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if n != 4 {
		t.Fatalf("deleteRange removed %d, want 4 (1 visit + 3 queries)", n)
	}
}
