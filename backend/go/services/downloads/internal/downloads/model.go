package downloads

import (
	"time"

	"github.com/google/uuid"
)

type State string

const (
	InProgress  State = "IN_PROGRESS"
	Paused      State = "PAUSED"
	Completed   State = "COMPLETED"
	Cancelled   State = "CANCELLED"
	Interrupted State = "INTERRUPTED"
)

func ValidState(s string) bool {
	switch State(s) {
	case InProgress, Paused, Completed, Cancelled, Interrupted:
		return true
	default:
		return false
	}
}

func (s State) IsTerminal() bool {
	return s == Completed || s == Cancelled || s == Interrupted
}

func (s State) CanTransitionTo(next State) bool {
	if s == next {
		return true
	}
	switch s {
	case InProgress:
		return true
	case Paused:
		return next != Completed
	case Completed, Cancelled:
		return false
	case Interrupted:
		return next == InProgress
	default:
		return false
	}
}

type Record struct {
	ID            uuid.UUID
	Username      string
	SourceURL     string
	FileName      string
	MimeType      string
	TotalBytes    *int64
	ReceivedBytes int64
	State         State
	LocalPath     string
	DeviceID      string
	StartedAt     time.Time
	FinishedAt    *time.Time
	UpdatedAt     time.Time
}

type PublicView struct {
	ID            uuid.UUID  `json:"id"`
	SourceURL     string     `json:"sourceUrl"`
	FileName      string     `json:"fileName"`
	MimeType      string     `json:"mimeType"`
	TotalBytes    *int64     `json:"totalBytes"`
	ReceivedBytes int64      `json:"receivedBytes"`
	Percent       *int       `json:"percent"`
	State         State      `json:"state"`
	OnThisDevice  bool       `json:"onThisDevice"`
	StartedAt     time.Time  `json:"startedAt"`
	FinishedAt    *time.Time `json:"finishedAt"`
}

func (r Record) percent() *int {
	if r.TotalBytes == nil || *r.TotalBytes <= 0 {
		return nil
	}
	p := int(r.ReceivedBytes * 100 / *r.TotalBytes)
	if p > 100 {
		p = 100
	}
	return &p
}

func (r Record) ToPublic(requestingDeviceID string) PublicView {
	return PublicView{
		ID:            r.ID,
		SourceURL:     r.SourceURL,
		FileName:      r.FileName,
		MimeType:      r.MimeType,
		TotalBytes:    r.TotalBytes,
		ReceivedBytes: r.ReceivedBytes,
		Percent:       r.percent(),
		State:         r.State,
		OnThisDevice:  r.DeviceID != "" && r.DeviceID == requestingDeviceID,
		StartedAt:     r.StartedAt,
		FinishedAt:    r.FinishedAt,
	}
}
