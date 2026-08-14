// Package store là tầng bền vững của service: bộ nhớ đệm phản hồi và sổ đếm
// lượt gọi ra ngoài.
//
// <h3>Vì sao lại nằm trong CSDL của backend chính</h3>
//
// Bản gốc (Prisma + MySQL) dựng một CSDL riêng. Ở đây nó là một SCHEMA tên
// `football` bên trong đúng CSDL `vnsearch` mà backend Java đang dùng. Đổi như
// vậy vì một hệ CSDL thứ hai kéo theo một bản sao lưu thứ hai, một bộ thông
// số thứ hai, một chỗ hết đĩa thứ hai — trong khi tổng dữ liệu ở đây chưa tới
// vài nghìn dòng.
//
// Schema riêng vẫn giữ được ranh giới quan trọng: hai service không bao giờ
// đọc bảng của nhau, và cắt service này ra CSDL riêng về sau chỉ là đổi một
// chuỗi kết nối.
//
// <h3>Vì sao tự migrate lúc khởi động</h3>
//
// Không dùng tệp trong /docker-entrypoint-initdb.d như backend chính: thư mục
// đó CHỈ chạy khi thư mục dữ liệu của Postgres còn rỗng. Ai đã chạy
// `docker compose up` một lần trước đây thì volume đã khởi tạo xong, và tệp
// mới thêm vào sẽ không bao giờ chạy — service khởi động lên rồi chết vì
// không có bảng. Câu lệnh CREATE ... IF NOT EXISTS ngay trong tiến trình chạy
// đúng trên cả volume mới lẫn volume cũ.
package store

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const schemaSQL = `
CREATE SCHEMA IF NOT EXISTS football;

CREATE TABLE IF NOT EXISTS football.api_cache (
    cache_key  TEXT PRIMARY KEY,
    payload    JSONB       NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- NULL = không bao giờ hết hạn. Dùng cho dữ liệu mùa giải đã khép lại:
    -- kết quả của mùa 2023 sẽ không đổi nữa, nên hỏi lại nhà cung cấp là tiêu
    -- hạn mức để nhận về đúng thứ đang có.
    expires_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS api_cache_expires_at_idx
    ON football.api_cache (expires_at);

CREATE TABLE IF NOT EXISTS football.api_call_log (
    id        BIGSERIAL   PRIMARY KEY,
    endpoint  TEXT        NOT NULL,
    params    TEXT        NOT NULL,
    called_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Chỉ mục này KHÔNG phải để tăng tốc: bảng chỉ có tối đa vài trăm dòng mỗi
-- ngày. Nó tồn tại vì truy vấn đếm hạn mức chạy TRƯỚC MỖI lượt gọi cache
-- miss, và một lần quét toàn bảng ở đó sẽ chậm dần theo tuổi của hệ thống.
CREATE INDEX IF NOT EXISTS api_call_log_called_at_idx
    ON football.api_call_log (called_at);

-- Cấu hình đặt lúc chạy, hiện chỉ có khoá API dán từ giao diện.
--
-- Nằm trong CSDL chứ không phải một tệp: service chạy trong container, mà
-- container thì bị xoá và dựng lại ở mỗi lần compose up. Ghi ra tệp trong
-- container là ghi vào một thứ sắp biến mất, còn CSDL thì nằm trên volume và
-- sống lâu hơn cả cụm.
--
-- (Không dùng dấu nháy ngược trong khối chú thích này: cả chuỗi SQL nằm trong
-- một raw string literal, nên một dấu nháy ngược sẽ cắt đứt chuỗi giữa chừng.)
CREATE TABLE IF NOT EXISTS football.settings (
    name       TEXT        PRIMARY KEY,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
`

// Store bọc một pool kết nối Postgres.
type Store struct {
	pool *pgxpool.Pool
}

// Entry là một bản ghi trong bộ nhớ đệm.
type Entry struct {
	Payload   []byte
	FetchedAt time.Time
	ExpiresAt *time.Time
}

// Expired cho biết bản ghi đã quá hạn tại thời điểm now.
func (e Entry) Expired(now time.Time) bool {
	return e.ExpiresAt != nil && e.ExpiresAt.Before(now)
}

// Open mở pool và kiểm tra kết nối.
func Open(ctx context.Context, databaseURL string) (*Store, error) {
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		return nil, fmt.Errorf("mở pool Postgres: %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("ping Postgres: %w", err)
	}
	return &Store{pool: pool}, nil
}

// Migrate tạo schema và bảng nếu chưa có. An toàn khi gọi lại nhiều lần.
func (s *Store) Migrate(ctx context.Context) error {
	if _, err := s.pool.Exec(ctx, schemaSQL); err != nil {
		return fmt.Errorf("tạo schema football: %w", err)
	}
	return nil
}

// Close trả lại mọi kết nối.
func (s *Store) Close() { s.pool.Close() }

// Ping dùng cho endpoint kiểm tra sức khoẻ.
func (s *Store) Ping(ctx context.Context) error { return s.pool.Ping(ctx) }

// Get đọc một bản ghi đệm. Trả found=false khi không có khoá.
func (s *Store) Get(ctx context.Context, key string) (Entry, bool, error) {
	var entry Entry
	err := s.pool.QueryRow(ctx,
		`SELECT payload, fetched_at, expires_at FROM football.api_cache WHERE cache_key = $1`,
		key,
	).Scan(&entry.Payload, &entry.FetchedAt, &entry.ExpiresAt)

	if errors.Is(err, pgx.ErrNoRows) {
		return Entry{}, false, nil
	}
	if err != nil {
		return Entry{}, false, fmt.Errorf("đọc đệm %q: %w", key, err)
	}
	return entry, true, nil
}

// Put ghi đè một bản ghi đệm. expiresAt nil nghĩa là không hết hạn.
func (s *Store) Put(ctx context.Context, key string, payload []byte, expiresAt *time.Time) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO football.api_cache (cache_key, payload, fetched_at, expires_at)
		VALUES ($1, $2, now(), $3)
		ON CONFLICT (cache_key) DO UPDATE
		SET payload = EXCLUDED.payload,
		    fetched_at = EXCLUDED.fetched_at,
		    expires_at = EXCLUDED.expires_at
	`, key, payload, expiresAt)
	if err != nil {
		return fmt.Errorf("ghi đệm %q: %w", key, err)
	}
	return nil
}

// RecordCall ghi một lượt gọi ra ngoài vào sổ.
func (s *Store) RecordCall(ctx context.Context, endpoint, params string) error {
	_, err := s.pool.Exec(ctx,
		`INSERT INTO football.api_call_log (endpoint, params) VALUES ($1, $2)`,
		endpoint, params,
	)
	if err != nil {
		return fmt.Errorf("ghi sổ lượt gọi: %w", err)
	}
	return nil
}

// Setting đọc một giá trị cấu hình. Trả chuỗi rỗng khi chưa có.
func (s *Store) Setting(ctx context.Context, name string) (string, error) {
	var value string
	err := s.pool.QueryRow(ctx,
		`SELECT value FROM football.settings WHERE name = $1`, name,
	).Scan(&value)

	if errors.Is(err, pgx.ErrNoRows) {
		return "", nil
	}
	if err != nil {
		return "", fmt.Errorf("đọc cấu hình %q: %w", name, err)
	}
	return value, nil
}

// PutSetting ghi đè một giá trị cấu hình.
func (s *Store) PutSetting(ctx context.Context, name, value string) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO football.settings (name, value, updated_at)
		VALUES ($1, $2, now())
		ON CONFLICT (name) DO UPDATE
		SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at
	`, name, value)
	if err != nil {
		return fmt.Errorf("ghi cấu hình %q: %w", name, err)
	}
	return nil
}

// CallsSince đếm số lượt gọi từ mốc thời gian cho trước.
func (s *Store) CallsSince(ctx context.Context, since time.Time) (int, error) {
	var count int
	err := s.pool.QueryRow(ctx,
		`SELECT count(*) FROM football.api_call_log WHERE called_at >= $1`,
		since,
	).Scan(&count)
	if err != nil {
		return 0, fmt.Errorf("đếm lượt gọi: %w", err)
	}
	return count, nil
}
