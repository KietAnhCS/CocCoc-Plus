package history

import (
	"time"

	"go.mongodb.org/mongo-driver/bson/primitive"
)

type Visit struct {
	ID         primitive.ObjectID `bson:"_id,omitempty" json:"id"`
	Username   string             `bson:"username" json:"username"`
	URL        string             `bson:"url" json:"url"`
	Title      string             `bson:"title" json:"title"`
	Host       string             `bson:"host" json:"host"`
	VisitedAt  time.Time          `bson:"visitedAt" json:"visitedAt"`
	VisitCount int                `bson:"visitCount" json:"visitCount"`
	Incognito  bool               `bson:"incognito" json:"incognito"`
}

type SearchQuery struct {
	ID          primitive.ObjectID `bson:"_id,omitempty" json:"id"`
	Username    string             `bson:"username" json:"username"`
	Query       string             `bson:"query" json:"query"`
	Normalized  string             `bson:"normalized" json:"normalized"`
	ResultCount int                `bson:"resultCount" json:"resultCount"`
	SearchedAt  time.Time          `bson:"searchedAt" json:"searchedAt"`
}

type Page[T any] struct {
	Content          []T   `json:"content"`
	Number           int   `json:"number"`
	Size             int   `json:"size"`
	TotalElements    int64 `json:"totalElements"`
	TotalPages       int   `json:"totalPages"`
	First            bool  `json:"first"`
	Last             bool  `json:"last"`
	NumberOfElements int   `json:"numberOfElements"`
	Empty            bool  `json:"empty"`
}

func newPage[T any](content []T, number, size int, total int64) Page[T] {
	if content == nil {
		content = []T{}
	}
	totalPages := 0
	if size > 0 {
		totalPages = int((total + int64(size) - 1) / int64(size))
	}
	return Page[T]{
		Content:          content,
		Number:           number,
		Size:             size,
		TotalElements:    total,
		TotalPages:       totalPages,
		First:            number == 0,
		Last:             number >= totalPages-1,
		NumberOfElements: len(content),
		Empty:            len(content) == 0,
	}
}
