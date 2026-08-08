# VnSearch

Máy tìm kiếm tiếng Việt tự cài đặt từ đầu — crawler, chỉ mục đảo, xếp hạng, và
một trình duyệt mini để tra cứu.

Mọi cấu trúc dữ liệu và thuật toán lõi đều **tự viết**, không dùng thư viện tìm
kiếm có sẵn: chỉ mục đảo, nén VByte, PageRank, Trie, Bloom Filter, MinHeap, và
bộ tách từ tiếng Việt.

```
┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│   Crawler    │───▶│    Chỉ mục   │───▶│   Xếp hạng   │───▶│   REST API   │
│              │    │              │    │              │    │              │
│ UrlFrontier  │    │ InvertedIndex│    │ TF-IDF/BM25  │    │ /api/search  │
│ BloomFilter  │    │ VByte + delta│    │ PageRank     │    │ /api/suggest │
│ robots.txt   │    │ Tách từ VN   │    │ MinHeap top-K│    │ /api/admin   │
└──────────────┘    └──────────────┘    └──────────────┘    └──────┬───────┘
                                                                    │
                                                            ┌───────▼───────┐
                                                            │  browser-app  │
                                                            │  (Electron)   │
                                                            └───────────────┘
```

---

## Chạy nhanh nhất — Docker

Cần: Docker Desktop.

```bash
# 1. Tạo tệp cấu hình từ mẫu
cp .env.example .env

# 2. Sinh khoá quản trị và dán vào .env
openssl rand -hex 32
#   PowerShell: -join ((1..64) | % { '{0:x}' -f (Get-Random -Max 16) })

# 3. Chạy
docker compose up -d --build
```

Backend phục vụ ở `http://localhost:8080`. Lần khởi động đầu mất vài chục giây
để lập chỉ mục — `docker compose logs -f backend` để theo dõi.

```bash
curl "http://localhost:8080/api/health"
curl "http://localhost:8080/api/search?q=máy+tính&size=3"
```

> **Nếu `docker compose up` dừng ngay với thông báo "Thieu ADMIN_API_KEY"** —
> đó là hành vi cố ý, không phải lỗi. Bước 2 ở trên chưa được làm.
> Xem [Vì sao khoá là bắt buộc](#vì-sao-khoá-quản-trị-là-bắt-buộc).

---

## Chạy trên máy thật (không Docker)

Cần: JDK 17+, Node.js 22+.

### Backend

```bash
# Đặt khoá quản trị cho phiên terminal hiện tại
export ADMIN_API_KEY=$(openssl rand -hex 32)          # Linux/macOS
$env:ADMIN_API_KEY = "..."                             # PowerShell

cd search-engine
./mvnw spring-boot:run
```

Không cần cơ sở dữ liệu: ứng dụng tự lùi về corpus mẫu đi kèm repo
(`data/seed-documents.json`), nên vừa clone về là chạy được ngay.

### Frontend

```bash
run-frontend.bat            # Windows
# hoặc: cd browser-app && npm install && npm run dev
```

### Crawl corpus riêng

```bash
run-crawl.bat 5000 3        # 5.000 trang, độ sâu 3
```

---

## API

| Endpoint | Cần khoá? | Mô tả |
|---|:---:|---|
| `GET /api/search?q=&page=&size=` | — | Tìm kiếm |
| `GET /api/suggest?q=&limit=` | — | Gợi ý theo tiền tố (Trie) |
| `GET /api/health` | — | Sống/chết. Trả `503` khi chỉ mục rỗng |
| `GET /actuator/prometheus` | — | Số liệu cho Prometheus |
| `POST /api/admin/crawl` | ✅ | Khởi động một phiên crawl |
| `GET /api/admin/crawl/{id}/status` | ✅ | Trạng thái phiên crawl |
| `POST /api/admin/reindex` | ✅ | Lập lại chỉ mục |
| `GET /api/admin/stats` | ✅ | Thống kê chi tiết |

Endpoint cần khoá thì gửi header `X-API-Key`:

```bash
curl -H "X-API-Key: $ADMIN_API_KEY" http://localhost:8080/api/admin/stats
```

Ví dụ đầy đủ: [`docs/api-examples.http`](docs/api-examples.http)

---

## Vì sao khoá quản trị là bắt buộc

`POST /api/admin/crawl` khiến máy chủ **đi tải một URL do người gọi chỉ định**,
rồi đưa nội dung vào chỉ mục công khai đọc được qua `GET /api/search`. Để mở là
một lỗ hổng SSRF hoàn chỉnh kèm sẵn kênh rút dữ liệu — trên máy ảo đám mây, một
request tới `169.254.169.254` trả về khoá IAM tạm thời.

Nên ứng dụng **cố ý không khởi động** khi thiếu khoá. Phương án còn lại — tự
sinh khoá rồi in ra log — tạo ra một hệ thống *có vẻ* đang chạy bình thường
trong khi không ai biết khoá là gì. Hỏng to còn hơn hỏng âm thầm.

Ba lớp bảo vệ độc lập, mỗi lớp chặn một thứ khác nhau:

| Lớp | Chặn gì | Cài ở đâu |
|---|---|---|
| API key (so sánh hằng thời gian) | Người lạ | `ApiKeyAuthFilter` |
| Chặn dải IP nội bộ **sau khi phân giải DNS** | URL trỏ vào mạng trong, kể cả khi đã có khoá | `SeedUrlValidator` |
| Chặn trên `maxPages` / `maxDepth` | Một request hợp lệ làm cạn tài nguyên | `AdminController` |
| Giới hạn tần suất (token bucket) | Gọi đúng cách nhưng quá nhanh | `RateLimitFilter` |

---

## Phát triển

```bash
cd search-engine && ./mvnw test       # 399 test
cd browser-app  && npm run typecheck && npm run lint
```

CI chạy cả ba việc trên mỗi lần push — xem [`.github/workflows/ci.yml`](.github/workflows/ci.yml).

### Cấu hình

Mọi biến môi trường đều có trong [`.env.example`](.env.example). Chỉ
`ADMIN_API_KEY` là bắt buộc; phần còn lại có mặc định hợp lý.

Đổi mô hình chấm điểm sang BM25 (MRR cao hơn — xem
[`docs/EVALUATION.md`](docs/EVALUATION.md)):

```bash
APP_RANKING_SCORER=bm25
```

---

## Tài liệu

| Tệp | Nội dung |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Kiến trúc tổng thể, luồng dữ liệu |
| [`docs/DSA-REPORT.md`](docs/DSA-REPORT.md) | Báo cáo cấu trúc dữ liệu & giải thuật |
| [`docs/Math/`](docs/Math/README.md) | Giải thích toán học từng khối, kèm sơ đồ tư duy |
| [`docs/EVALUATION.md`](docs/EVALUATION.md) | Đo chất lượng tìm kiếm (MRR, P@k, nDCG) |
| [`docs/SO-SANH-PHUONG-AN.md`](docs/SO-SANH-PHUONG-AN.md) | So sánh các phương án thiết kế |
| [`docs/DANH-GIA-DU-AN.md`](docs/DANH-GIA-DU-AN.md) | Rà soát chất lượng theo chuẩn doanh nghiệp |
| [`docs/CHAM-DIEM-STARTUP.md`](docs/CHAM-DIEM-STARTUP.md) | Chấm DSA · CI/CD · Bảo mật · Tối ưu theo thước đo startup |
| [`docs/FRONTEND.md`](docs/FRONTEND.md) | Trình duyệt mini (Electron + React) |

---

## Cấu trúc thư mục

```
search-engine/          Backend Spring Boot (Java 17)
  src/main/java/com/vnsearch/
    crawler/            Tải trang, lọc URL, hàng đợi hai tầng
    index/              Chỉ mục đảo, nén VByte, tách từ tiếng Việt
    query/              Phân tích truy vấn, hợp nhất posting list
    ranking/            TF-IDF, BM25, PageRank, sinh đoạn trích
    datastructure/      Trie, BloomFilter, MinHeap, LRUCache, SparseMatrix
    eval/               Bộ đo chất lượng tìm kiếm
browser-app/            Trình duyệt mini (Electron + React + TypeScript)
docs/                   Tài liệu
```
