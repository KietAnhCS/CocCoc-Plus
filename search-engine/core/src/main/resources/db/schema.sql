-- Lược đồ CSDL cho VnSearch.
--
-- NGUYÊN TẮC QUAN TRỌNG: PostgreSQL ở đây chỉ đóng vai trò KHO LƯU TRỮ tài
-- liệu thô. Chỉ mục đảo, Trie, LRU cache, PageRank vẫn do đồ án tự cài đặt
-- và nằm trong bộ nhớ — nếu đẩy việc tìm kiếm sang full-text search của
-- PostgreSQL thì toàn bộ phần cấu trúc dữ liệu tự cài, vốn là nội dung
-- chính của đồ án, sẽ trở nên vô nghĩa.
--
-- Vì sao vẫn cần CSDL: corpus 5.011 tài liệu đã tạo ra file JSON 62MB. Nạp
-- toàn bộ file đó bằng Jackson đòi hỏi giữ đồng thời cả chuỗi JSON lẫn cây
-- đối tượng trong RAM. Ở quy mô hàng chục nghìn trang, cách này không còn
-- khả thi, trong khi CSDL cho phép đọc theo lô và truy vấn có chọn lọc.

CREATE TABLE IF NOT EXISTS documents (
    doc_id           INTEGER PRIMARY KEY,
    url              TEXT NOT NULL UNIQUE,
    title            TEXT,
    meta_description TEXT,
    body_text        TEXT,
    crawled_at       TIMESTAMPTZ
);

-- Bảng liên kết tách riêng thay vì nhét mảng vào cột: đây chính là các cạnh
-- của đồ thị web dùng cho PageRank, và tách bảng cho phép truy vấn/thống kê
-- đồ thị bằng SQL (đếm bậc vào, tìm liên kết chéo domain...) mà không phải
-- nạp toàn bộ tài liệu lên.
CREATE TABLE IF NOT EXISTS outlinks (
    from_doc_id INTEGER NOT NULL REFERENCES documents(doc_id) ON DELETE CASCADE,
    to_url      TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_outlinks_from ON outlinks (from_doc_id);
CREATE INDEX IF NOT EXISTS idx_outlinks_to   ON outlinks (to_url);

-- ---------------------------------------------------------------------------
-- Cột phục vụ THÍ NGHIỆM ĐỐI CHỨNG, không phục vụ chức năng tìm kiếm chính.
--
-- tsvector + chỉ mục GIN của PostgreSQL bản chất cũng là một chỉ mục đảo,
-- nhưng do một hệ quản trị CSDL trưởng thành cài đặt. Giữ nó song song với
-- chỉ mục đảo tự cài cho phép so sánh sòng phẳng trên CÙNG một corpus về
-- tốc độ truy vấn và kích thước chỉ mục — đây là baseline mà một đồ án
-- nghiêm túc cần có, thay vì chỉ tự khẳng định cài đặt của mình là tốt.
--
-- Lưu ý: dùng cấu hình 'simple' chứ không phải 'english', vì bộ stemmer
-- tiếng Anh sẽ cắt gốc từ sai hoàn toàn trên tiếng Việt.
-- ---------------------------------------------------------------------------
ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS tsv tsvector
    GENERATED ALWAYS AS (
        to_tsvector('simple',
            coalesce(title, '') || ' ' ||
            coalesce(meta_description, '') || ' ' ||
            coalesce(body_text, ''))
    ) STORED;

CREATE INDEX IF NOT EXISTS idx_documents_tsv ON documents USING GIN (tsv);
