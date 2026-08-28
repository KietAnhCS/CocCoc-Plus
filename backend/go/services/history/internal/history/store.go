package history

import (
	"context"
	"time"

	"go.mongodb.org/mongo-driver/bson"
	"go.mongodb.org/mongo-driver/bson/primitive"
	"go.mongodb.org/mongo-driver/mongo"
	"go.mongodb.org/mongo-driver/mongo/options"
)

type Store struct {
	db      *mongo.Database
	visits  *mongo.Collection
	queries *mongo.Collection
	audits  *mongo.Collection
}

func NewStore(db *mongo.Database) *Store {
	return &Store{
		db:      db,
		visits:  db.Collection("visits"),
		queries: db.Collection("search_queries"),
		audits:  db.Collection("audit_log"),
	}
}

func (s *Store) Ping(ctx context.Context) error {
	return s.db.Client().Ping(ctx, nil)
}

func (s *Store) EnsureIndexes(ctx context.Context) error {
	day := int32(86400)
	_, err := s.visits.Indexes().CreateMany(ctx, []mongo.IndexModel{
		{Keys: bson.D{{Key: "username", Value: 1}, {Key: "visitedAt", Value: -1}},
			Options: options.Index().SetName("ix_visits_user_time")},
		{Keys: bson.D{{Key: "username", Value: 1}, {Key: "url", Value: 1}},
			Options: options.Index().SetName("ix_visits_user_url")},
		{Keys: bson.D{{Key: "visitedAt", Value: 1}},
			Options: options.Index().SetName("ix_visits_ttl").SetExpireAfterSeconds(90 * day)},
	})
	if err != nil {
		return err
	}
	_, err = s.queries.Indexes().CreateMany(ctx, []mongo.IndexModel{
		{Keys: bson.D{{Key: "username", Value: 1}, {Key: "searchedAt", Value: -1}},
			Options: options.Index().SetName("ix_queries_user_time")},
		{Keys: bson.D{{Key: "username", Value: 1}, {Key: "normalized", Value: 1}},
			Options: options.Index().SetName("ix_queries_user_prefix")},
		{Keys: bson.D{{Key: "searchedAt", Value: 1}},
			Options: options.Index().SetName("ix_queries_ttl").SetExpireAfterSeconds(30 * day)},
	})
	return err
}

// ------------------------------------------------------------------- visits

func (s *Store) FindVisitByURL(ctx context.Context, username, url string) (*Visit, error) {
	var v Visit
	err := s.visits.FindOne(ctx, bson.M{"username": username, "url": url}).Decode(&v)
	if err == mongo.ErrNoDocuments {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &v, nil
}

func (s *Store) UpsertVisit(ctx context.Context, v Visit) (Visit, error) {
	if v.ID.IsZero() {
		v.ID = primitive.NewObjectID()
		if _, err := s.visits.InsertOne(ctx, v); err != nil {
			return Visit{}, err
		}
		return v, nil
	}
	_, err := s.visits.ReplaceOne(ctx, bson.M{"_id": v.ID}, v)
	return v, err
}

func (s *Store) ListVisits(ctx context.Context, filter bson.M, page, size int) ([]Visit, int64, error) {
	total, err := s.visits.CountDocuments(ctx, filter)
	if err != nil {
		return nil, 0, err
	}
	opt := options.Find().
		SetSort(bson.D{{Key: "visitedAt", Value: -1}}).
		SetSkip(int64(page * size)).
		SetLimit(int64(size))
	cur, err := s.visits.Find(ctx, filter, opt)
	if err != nil {
		return nil, 0, err
	}
	var out []Visit
	if err := cur.All(ctx, &out); err != nil {
		return nil, 0, err
	}
	return out, total, nil
}

func (s *Store) DeleteVisit(ctx context.Context, id primitive.ObjectID, username string) (int64, error) {
	res, err := s.visits.DeleteOne(ctx, bson.M{"_id": id, "username": username})
	if err != nil {
		return 0, err
	}
	return res.DeletedCount, nil
}

func (s *Store) DeleteVisitsBetween(ctx context.Context, username string, from, to time.Time) (int64, error) {
	res, err := s.visits.DeleteMany(ctx, bson.M{
		"username":  username,
		"visitedAt": bson.M{"$gte": from, "$lte": to},
	})
	if err != nil {
		return 0, err
	}
	return res.DeletedCount, nil
}

func (s *Store) CountVisits(ctx context.Context, username string) (int64, error) {
	return s.visits.CountDocuments(ctx, bson.M{"username": username})
}

// ------------------------------------------------------------ search queries

func (s *Store) FindQueryByNormalized(ctx context.Context, username, normalized string) (*SearchQuery, error) {
	var q SearchQuery
	err := s.queries.FindOne(ctx, bson.M{"username": username, "normalized": normalized}).Decode(&q)
	if err == mongo.ErrNoDocuments {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &q, nil
}

func (s *Store) UpsertQuery(ctx context.Context, q SearchQuery) (SearchQuery, error) {
	if q.ID.IsZero() {
		q.ID = primitive.NewObjectID()
		if _, err := s.queries.InsertOne(ctx, q); err != nil {
			return SearchQuery{}, err
		}
		return q, nil
	}
	_, err := s.queries.ReplaceOne(ctx, bson.M{"_id": q.ID}, q)
	return q, err
}

func (s *Store) ListQueries(ctx context.Context, username string, page, size int) ([]SearchQuery, int64, error) {
	filter := bson.M{"username": username}
	total, err := s.queries.CountDocuments(ctx, filter)
	if err != nil {
		return nil, 0, err
	}
	opt := options.Find().
		SetSort(bson.D{{Key: "searchedAt", Value: -1}}).
		SetSkip(int64(page * size)).
		SetLimit(int64(size))
	cur, err := s.queries.Find(ctx, filter, opt)
	if err != nil {
		return nil, 0, err
	}
	var out []SearchQuery
	if err := cur.All(ctx, &out); err != nil {
		return nil, 0, err
	}
	return out, total, nil
}

func (s *Store) SuggestQueries(ctx context.Context, username, anchoredPattern string, limit int) ([]SearchQuery, error) {
	filter := bson.M{
		"username":   username,
		"normalized": bson.M{"$regex": primitive.Regex{Pattern: anchoredPattern, Options: "i"}},
	}
	opt := options.Find().SetSort(bson.D{{Key: "searchedAt", Value: -1}}).SetLimit(int64(limit))
	cur, err := s.queries.Find(ctx, filter, opt)
	if err != nil {
		return nil, err
	}
	var out []SearchQuery
	if err := cur.All(ctx, &out); err != nil {
		return nil, err
	}
	return out, nil
}

func (s *Store) DeleteQueriesBetween(ctx context.Context, username string, from, to time.Time) (int64, error) {
	res, err := s.queries.DeleteMany(ctx, bson.M{
		"username":   username,
		"searchedAt": bson.M{"$gte": from, "$lte": to},
	})
	if err != nil {
		return 0, err
	}
	return res.DeletedCount, nil
}

// -------------------------------------------------------------------- audit

func (s *Store) RecordAudit(ctx context.Context, subject, action, resource, outcome, detail string) error {
	_, err := s.audits.InsertOne(ctx, bson.M{
		"occurredAt": time.Now().UTC(),
		"subject":    subject,
		"action":     action,
		"resource":   resource,
		"outcome":    outcome,
		"detail":     detail,
	})
	return err
}
