package downloads

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/google/uuid"
)

const maxPageSize = 200

type InvalidTransitionError struct {
	From, To State
}

func (e *InvalidTransitionError) Error() string {
	return fmt.Sprintf("Không chuyển được từ %s sang %s.", e.From, e.To)
}

type repo interface {
	Save(ctx context.Context, rec Record) error
	Find(ctx context.Context, id uuid.UUID, username string) (*Record, error)
	FindByUser(ctx context.Context, username string, offset, limit int) ([]Record, error)
	FindActive(ctx context.Context, username string) ([]Record, error)
	Delete(ctx context.Context, id uuid.UUID, username string) (bool, error)
	DeleteFinished(ctx context.Context, username string) (int, error)
	Count(ctx context.Context, username string) (int, error)
}

type Service struct {
	repo  repo
	clock func() time.Time
}

func NewService(r repo, clock func() time.Time) *Service {
	if clock == nil {
		clock = func() time.Time { return time.Now().UTC() }
	}
	return &Service{repo: r, clock: clock}
}

func (s *Service) Start(ctx context.Context, username string, id uuid.UUID, sourceURL, fileName,
	mimeType string, totalBytes *int64, localPath, deviceID string) (Record, error) {

	if existing, err := s.repo.Find(ctx, id, username); err != nil {
		return Record{}, err
	} else if existing != nil {
		return *existing, nil
	}
	now := s.clock()
	rec := Record{
		ID: id, Username: username, SourceURL: sourceURL, FileName: fileName, MimeType: mimeType,
		TotalBytes: totalBytes, ReceivedBytes: 0, State: InProgress, LocalPath: localPath,
		DeviceID: deviceID, StartedAt: now, FinishedAt: nil, UpdatedAt: now,
	}
	if err := s.repo.Save(ctx, rec); err != nil {
		return Record{}, err
	}
	return rec, nil
}

func (s *Service) Update(ctx context.Context, username string, id uuid.UUID, receivedBytes *int64,
	newState *State, localPath *string) (*Record, error) {

	cur, err := s.repo.Find(ctx, id, username)
	if err != nil || cur == nil {
		return nil, err
	}

	next := cur.State
	if newState != nil {
		next = *newState
	}
	if !cur.State.CanTransitionTo(next) {
		return nil, &InvalidTransitionError{From: cur.State, To: next}
	}

	bytes := cur.ReceivedBytes
	if receivedBytes != nil && *receivedBytes > bytes {
		bytes = *receivedBytes
	}

	var finished *time.Time
	if next.IsTerminal() {
		if cur.FinishedAt != nil {
			finished = cur.FinishedAt
		} else {
			t := s.clock()
			finished = &t
		}
	}

	path := cur.LocalPath
	if localPath != nil {
		path = *localPath
	}

	rec := Record{
		ID: cur.ID, Username: username, SourceURL: cur.SourceURL, FileName: cur.FileName,
		MimeType: cur.MimeType, TotalBytes: cur.TotalBytes, ReceivedBytes: bytes, State: next,
		LocalPath: path, DeviceID: cur.DeviceID, StartedAt: cur.StartedAt, FinishedAt: finished,
		UpdatedAt: s.clock(),
	}
	if err := s.repo.Save(ctx, rec); err != nil {
		return nil, err
	}
	return &rec, nil
}

func (s *Service) List(ctx context.Context, username string, page, size int) ([]Record, error) {
	limit := clamp(size)
	if page < 0 {
		page = 0
	}
	return s.repo.FindByUser(ctx, username, page*limit, limit)
}

func (s *Service) ListActive(ctx context.Context, username string) ([]Record, error) {
	return s.repo.FindActive(ctx, username)
}

func (s *Service) Delete(ctx context.Context, username string, id uuid.UUID) (bool, error) {
	return s.repo.Delete(ctx, id, username)
}

func (s *Service) DeleteFinished(ctx context.Context, username string) (int, error) {
	n, err := s.repo.DeleteFinished(ctx, username)
	if err == nil {
		slog.Info("xoá sổ tải xuống", "username", username, "deleted", n)
	}
	return n, err
}

func (s *Service) Count(ctx context.Context, username string) (int, error) {
	return s.repo.Count(ctx, username)
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
